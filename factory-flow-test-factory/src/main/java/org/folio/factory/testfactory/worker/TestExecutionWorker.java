package org.folio.factory.testfactory.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
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
@RequiredArgsConstructor
@Slf4j
public class TestExecutionWorker implements AgentWorker {

    public static final String ID = "test-execution-agent";

    static final int MAX_CONSOLE_LOG_CHARS = 20_000;

    private static final long EXECUTION_TIMEOUT_MINUTES = 15;

    private final ScriptBundleCodec bundleCodec;
    private final FrontmatterCodec frontmatterCodec;
    private final TestFactoryProperties properties;
    private final JsonMapper jsonMapper;

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
                To enable execution, set `FACTORY_TEST_FACTORY_EXECUTION_BASE_URL` and
                `FACTORY_TEST_FACTORY_EXECUTION_KARATE_JAR`.

                Review the generated scripts for correctness before sign-off.
                """;
        return frontmatterCodec.render(metadata, body);
    }

    private String executeWithKarate(ScriptBundle bundle) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("factory-test-factory-run");
            writeScripts(bundle, workDir);
            var run = runKarate(bundle, workDir);
            return executedResults(bundle, workDir, run.exitCode(), run.output());
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

    private static void writeScripts(ScriptBundle bundle, Path workDir) throws IOException {
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            var target = workDir.resolve(file.path()).normalize();
            if (!target.startsWith(workDir)) {
                throw new AgentExecutionException("Script path escapes work directory: " + file.path());
            }
            Files.createDirectories(target.getParent() == null ? workDir : target.getParent());
            Files.writeString(target, file.content());
        }
    }

    private KarateRun runKarate(ScriptBundle bundle, Path workDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "java", "-jar", properties.execution().karateJar(),
                "-o", workDir.resolve("reports").toString()));
        bundle.files().forEach(file -> command.add(file.path()));

        // Output goes to a file rather than a pipe: reading a pipe would block
        // until the child exits, which would make the timeout below dead code
        // for a hung run.
        var consoleLog = workDir.resolve("karate-console.log");
        var process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(consoleLog.toFile())
                .start();
        if (!process.waitFor(EXECUTION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AgentExecutionException("Karate execution timed out after "
                    + EXECUTION_TIMEOUT_MINUTES + " minutes");
        }
        var output = Files.exists(consoleLog) ? Files.readString(consoleLog) : "";
        log.info("Karate run finished with exit code {}", process.exitValue());
        return new KarateRun(process.exitValue(), output);
    }

    private void deleteRecursively(Path directory) {
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException e) {
            log.warn("Could not clean up temporary directory {}: {}", directory, e.getMessage());
        }
    }

    private String executedResults(ScriptBundle bundle, Path workDir, int exitCode, String consoleOutput) {
        var features = readFeatureSummary(workDir);
        var cases = caseResults(bundle, features.passedByFile(), exitCode);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", "EXECUTED");
        metadata.put("exit_code", exitCode);
        metadata.put("totals", Map.of("passed", cases.passed(), "failed", cases.failed(), "not_executed", 0));
        metadata.put("features", Map.of("passed", features.passed(), "failed", features.failed()));
        metadata.put("case_results", cases.rows());
        String body = "# Test Results\n\nKarate exit code: " + exitCode
                + "\n\n<details><summary>Console output</summary>\n\n````\n"
                + truncateConsole(consoleOutput.strip()) + "\n````\n</details>\n";
        return frontmatterCodec.render(metadata, body);
    }

    private FeatureSummary readFeatureSummary(Path workDir) {
        Map<String, Boolean> passedByFile = new LinkedHashMap<>();
        int passed = 0;
        int failed = 0;
        var summaryFile = workDir.resolve("reports").resolve("karate-summary-json.txt");
        try {
            JsonNode summary = jsonMapper.readTree(Files.readString(summaryFile));
            for (JsonNode feature : summary.path("featureSummary")) {
                boolean featureFailed = feature.path("failed").asBoolean(false);
                passedByFile.put(feature.path("relativePath").asString(""), !featureFailed);
                if (featureFailed) {
                    failed++;
                } else {
                    passed++;
                }
            }
        } catch (IOException e) {
            // Fall back to the process exit code when the summary is unavailable.
            log.warn("Karate summary not readable ({}); falling back to exit code", e.getMessage());
        }
        return new FeatureSummary(passedByFile, passed, failed);
    }

    private static CaseResults caseResults(ScriptBundle bundle, Map<String, Boolean> passedByFile, int exitCode) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            boolean filePass = passedByFile.getOrDefault(file.path(), exitCode == 0);
            for (String caseId : file.caseIds()) {
                rows.add(Map.of("case_id", caseId, "status", filePass ? "PASSED" : "FAILED", "file", file.path()));
                if (filePass) {
                    passed++;
                } else {
                    failed++;
                }
            }
        }
        return new CaseResults(rows, passed, failed);
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

    private record KarateRun(int exitCode, String output) {
    }

    private record FeatureSummary(Map<String, Boolean> passedByFile, int passed, int failed) {
    }

    private record CaseResults(List<Map<String, Object>> rows, int passed, int failed) {
    }
}
