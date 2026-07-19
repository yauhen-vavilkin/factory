package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.testfactory.TestFactoryProperties;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.factory.testfactory.worker.TestExecutionWorker.MAX_CONSOLE_LOG_CHARS;

class TestExecutionWorkerTest {

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(frontmatterCodec);

    private TestExecutionWorker worker(String baseUrl, String karateJar) {
        TestFactoryProperties properties = new TestFactoryProperties(
                new TestFactoryProperties.Execution(baseUrl, karateJar), "o/r", "main", null, 55L);
        return new TestExecutionWorker(bundleCodec, frontmatterCodec, properties,
                JsonMapper.builder().build());
    }

    private AgentContext contextWith(ScriptBundle bundle) {
        return new AgentContext(UUID.randomUUID(), "execute", Map.of(
                "test_scripts.md", new ArtifactContent("test_scripts.md", 1, "text/markdown",
                        bundleCodec.render(bundle))),
                null, Map.of(), List.of("test_results.md"));
    }

    @Test
    void unconfiguredExecutionProducesAdvisoryResultsForEveryCase() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01", "TC-02"),
                        "Feature: A\n  Scenario: TC-01 works"),
                new ScriptBundle.ScriptFile("features/b.feature", List.of("TC-03"),
                        "Feature: B\n  Scenario: TC-03 works")));

        AgentResult result = worker(null, null).execute(contextWith(bundle));

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("test_results.md"));
        assertThat(parsed.metadata().path("mode").asString("")).isEqualTo("ADVISORY");
        assertThat(parsed.metadata().path("totals").path("not_executed").asInt()).isEqualTo(3);
        List<String> statuses = new ArrayList<>();
        for (JsonNode caseResult : parsed.metadata().path("case_results")) {
            statuses.add(caseResult.path("status").asString(""));
        }
        assertThat(statuses).hasSize(3).containsOnly("NOT_EXECUTED");
        assertThat(parsed.body()).contains("FACTORY_TEST_FACTORY_EXECUTION_BASE_URL");
    }

    @Test
    void rejectsScriptPathEscapingWorkDirectoryBeforeLaunchingAnything() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("../evil.feature", List.of("TC-01"),
                        "Feature: E\n  Scenario: TC-01 escapes")));

        assertThatThrownBy(() -> worker("http://localhost:9999", "/no/such/karate.jar")
                .execute(contextWith(bundle)))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Script path escapes work directory")
                .hasMessageContaining("../evil.feature");
    }

    @Test
    void failedRunnerWithoutSummaryFallsBackToExitCodeAndMarksCasesFailed() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/a.feature", List.of("TC-01", "TC-02"),
                        "Feature: A\n  Scenario: TC-01 works")));

        AgentResult result = worker("http://localhost:9999", "/no/such/karate.jar")
                .execute(contextWith(bundle));

        Frontmatter parsed = frontmatterCodec.parse(result.outputs().get("test_results.md"));
        assertThat(parsed.metadata().path("mode").asString("")).isEqualTo("EXECUTED");
        assertThat(parsed.metadata().path("exit_code").asInt()).isNotZero();
        assertThat(parsed.metadata().path("totals").path("passed").asInt()).isZero();
        assertThat(parsed.metadata().path("totals").path("failed").asInt()).isEqualTo(2);
        assertThat(parsed.metadata().path("features").path("passed").asInt()).isZero();
        assertThat(parsed.metadata().path("features").path("failed").asInt()).isZero();
        List<String> statuses = new ArrayList<>();
        for (JsonNode caseResult : parsed.metadata().path("case_results")) {
            statuses.add(caseResult.path("status").asString(""));
        }
        assertThat(statuses).hasSize(2).containsOnly("FAILED");
        assertThat(parsed.body()).contains("<details><summary>Console output</summary>");
    }

    @Test
    void truncateConsole_shortOutput_returnedUnchanged() {
        String console = "Karate run finished: 3 passed, 0 failed";

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_exactlyAtLimit_returnedUnchanged() {
        String console = "x".repeat(MAX_CONSOLE_LOG_CHARS);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_overLimit_keepsTailAndSaysHowMuchWasOmitted() {
        String marker = "FINAL-KARATE-SUMMARY-MARKER-XY";
        String console = "a".repeat(MAX_CONSOLE_LOG_CHARS + 500 - marker.length()) + marker;

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).endsWith(marker);
        // Pin the full phrase: a bare contains("500") would also match the total-length
        // figure (20500) and pass with a wrong omitted-count computation.
        assertThat(result).contains("truncated")
                .contains("500 of " + console.length() + " characters omitted");
        // The input has no newlines, so the first blank line is the header/payload boundary.
        String payload = result.substring(result.indexOf("\n\n") + 2);
        assertThat(payload).isEqualTo(console.substring(console.length() - MAX_CONSOLE_LOG_CHARS));
    }

    @Test
    void truncateConsole_overLimit_boundedTotalLength() {
        String console = "b".repeat(MAX_CONSOLE_LOG_CHARS + 500);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result.length()).isLessThan(MAX_CONSOLE_LOG_CHARS + 200);
    }
}
