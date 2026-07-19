package org.folio.factory.flowa.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.flowa.FlowAProperties;
import org.folio.factory.flowa.artifact.ScriptBundleCodec;
import org.folio.factory.flowa.model.ScriptBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Non-LLM system worker. When a reference environment and Karate runner are
 * configured, materialises the script bundle to disk and executes it; otherwise
 * runs in advisory mode (the document's Phase 1 default) and records the results
 * as NOT_EXECUTED so the pipeline still reaches QA sign-off.
 */
public class TestExecutionWorker implements AgentWorker {

    public static final String ID = "test-execution-agent";

    private static final Logger log = LoggerFactory.getLogger(TestExecutionWorker.class);
    private static final long EXECUTION_TIMEOUT_MINUTES = 15;
    static final int MAX_CONSOLE_LOG_CHARS = 20_000;

    private final ScriptBundleCodec bundleCodec;
    private final FrontmatterCodec frontmatterCodec;
    private final FlowAProperties properties;
    private final JsonMapper jsonMapper;

    public TestExecutionWorker(ScriptBundleCodec bundleCodec, FrontmatterCodec frontmatterCodec,
                               FlowAProperties properties, JsonMapper jsonMapper) {
        this.bundleCodec = bundleCodec;
        this.frontmatterCodec = frontmatterCodec;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        ScriptBundle bundle = bundleCodec.parse(context.requireInput("test_scripts.md").content());

        if (!properties.execution().isConfigured()) {
            return AgentResult.of("test_results.md", advisoryResults(bundle));
        }
        return AgentResult.of("test_results.md", executeWithKarate(bundle));
    }

    private String advisoryResults(ScriptBundle bundle) {
        List<Map<String, Object>> caseResults = new ArrayList<>();
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            for (String caseId : file.caseIds()) {
                caseResults.add(Map.of("case_id", caseId, "status", "NOT_EXECUTED", "file", file.path()));
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "ADVISORY");
        metadata.put("totals", Map.of("passed", 0, "failed", 0, "not_executed", caseResults.size()));
        metadata.put("case_results", caseResults);
        String body = """
                # Test Results (Advisory Mode)

                No reference environment is configured, so the generated scripts were **not executed**.
                To enable execution, set `FACTORY_FLOWA_EXECUTION_BASE_URL` and
                `FACTORY_FLOWA_EXECUTION_KARATE_JAR`.

                Review the generated scripts for correctness before sign-off.
                """;
        return frontmatterCodec.render(metadata, body);
    }

    private String executeWithKarate(ScriptBundle bundle) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("factory-flowa-run");
            for (ScriptBundle.ScriptFile file : bundle.files()) {
                Path target = workDir.resolve(file.path()).normalize();
                if (!target.startsWith(workDir)) {
                    throw new AgentExecutionException("Script path escapes work directory: " + file.path());
                }
                Files.createDirectories(target.getParent() == null ? workDir : target.getParent());
                Files.writeString(target, file.content());
            }
            List<String> command = new ArrayList<>(List.of(
                    "java", "-jar", properties.execution().karateJar(),
                    "-o", workDir.resolve("reports").toString()));
            bundle.files().forEach(f -> command.add(f.path()));

            // Output goes to a file rather than a pipe: reading a pipe would block
            // until the child exits, which would make the timeout below dead code
            // for a hung run.
            Path consoleLog = workDir.resolve("karate-console.log");
            Process process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(consoleLog.toFile())
                    .start();
            if (!process.waitFor(EXECUTION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AgentExecutionException("Karate execution timed out after "
                        + EXECUTION_TIMEOUT_MINUTES + " minutes");
            }
            String output = Files.exists(consoleLog) ? Files.readString(consoleLog) : "";
            log.info("Karate run finished with exit code {}", process.exitValue());
            return executedResults(bundle, workDir, process.exitValue(), output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AgentExecutionException("Karate execution failed: " + e.getMessage(), e);
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    private void deleteRecursively(Path directory) {
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException e) {
            log.warn("Could not clean up temporary directory {}: {}", directory, e.getMessage());
        }
    }

    private String executedResults(ScriptBundle bundle, Path workDir, int exitCode, String consoleOutput) {
        Map<String, Boolean> filePassed = new LinkedHashMap<>();
        int passedFeatures = 0;
        int failedFeatures = 0;
        Path summaryFile = workDir.resolve("reports").resolve("karate-summary-json.txt");
        try {
            JsonNode summary = jsonMapper.readTree(Files.readString(summaryFile));
            for (JsonNode feature : summary.path("featureSummary")) {
                boolean failed = feature.path("failed").asBoolean(false);
                filePassed.put(feature.path("relativePath").asString(""), !failed);
                if (failed) {
                    failedFeatures++;
                } else {
                    passedFeatures++;
                }
            }
        } catch (IOException e) {
            // Fall back to the process exit code when the summary is unavailable.
            log.warn("Karate summary not readable ({}); falling back to exit code", e.getMessage());
        }

        List<Map<String, Object>> caseResults = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            boolean filePass = filePassed.getOrDefault(file.path(), exitCode == 0);
            for (String caseId : file.caseIds()) {
                caseResults.add(Map.of("case_id", caseId,
                        "status", filePass ? "PASSED" : "FAILED", "file", file.path()));
                if (filePass) {
                    passed++;
                } else {
                    failed++;
                }
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "EXECUTED");
        metadata.put("exit_code", exitCode);
        metadata.put("totals", Map.of("passed", passed, "failed", failed, "not_executed", 0));
        metadata.put("features", Map.of("passed", passedFeatures, "failed", failedFeatures));
        metadata.put("case_results", caseResults);
        String body = "# Test Results\n\nKarate exit code: " + exitCode
                + "\n\n<details><summary>Console output</summary>\n\n````\n"
                + truncateConsole(consoleOutput.strip()) + "\n````\n</details>\n";
        return frontmatterCodec.render(metadata, body);
    }

    /**
     * Bounds the console log embedded into the artifact, keeping the tail (where
     * Karate's summary and last failures are). Authoritative pass/fail data lives in
     * the frontmatter, parsed from the summary JSON, so truncation only trims the
     * human-readable diagnostic. Defence-in-depth in front of the max-artifact-bytes cap.
     */
    static String truncateConsole(String console) {
        if (console.length() <= MAX_CONSOLE_LOG_CHARS) {
            return console;
        }
        int omitted = console.length() - MAX_CONSOLE_LOG_CHARS;
        return "[console output truncated: " + omitted + " of " + console.length()
                + " characters omitted; showing last " + MAX_CONSOLE_LOG_CHARS + "]\n\n"
                + console.substring(console.length() - MAX_CONSOLE_LOG_CHARS);
    }
}
