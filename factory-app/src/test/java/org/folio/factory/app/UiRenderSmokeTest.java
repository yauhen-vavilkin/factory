package org.folio.factory.app;

import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that actually renders the Thymeleaf templates end to end;
 * standalone MockMvc controller tests never resolve the layout/component
 * fragments. Boots the app, seeds one execution + review, and asserts every
 * UI page renders through the shared layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
                "factory.developer-pricing.rates.test.model=test-model",
                "factory.developer-pricing.rates.test.currency=USD",
                "factory.developer-pricing.rates.test.as-of=2026-09-19",
                "factory.developer-pricing.rates.test.source=https://example.test/pricing",
                "factory.developer-pricing.rates.test.input-per-million=0.15",
                "factory.developer-pricing.rates.test.cache-read-per-million=0.03",
                "factory.developer-pricing.rates.test.output-per-million=0.50"})
@Import(StubLlmConfiguration.class)
@Testcontainers
class UiRenderSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort
    int port;

    @Autowired
    private PipelineExecutionRepository executions;

    @Autowired
    private HitlReviewRepository reviews;

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private ITemplateEngine templateEngine;

    private RestClient rest;

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void dashboardRenders() {
        String body = assertRendered("/", "Dashboard");
        assertThat(body)
                .contains("chart.umd.js")
                .contains("/js/charts.js")
                .contains("class=\"charts-grid\"");
    }

    @Test
    void dashboardRendersTokenKpiAndBreakdownTable() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "test-automation", "engine",
                Map.of("promptTokens", 1786, "completionTokens", 353));

        String body = assertRendered("/", "LLM tokens");
        assertThat(body)
                .contains("Token spend by step")
                // Grouped thousands, and the per-step row reached the table body.
                .contains("2,139")
                .contains("test-automation");
    }

    @Test
    void chartJsWebjarServes() {
        ResponseEntity<String> response = rest.get()
                .uri("/webjars/chart.js/dist/chart.umd.js")
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Chart");
    }

    @Test
    void reviewsListRenders() {
        assertRendered("/reviews", "Human-in-the-loop gates and their decisions.");
    }

    @Test
    void executionsListRenders() {
        assertRendered("/executions", "Pipeline runs across all flows, most recent first.");
    }

    @Test
    void reviewsAllFilterRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "gate-2-signoff", 5,
                "{\"title\":\"Signed-off plan\",\"flowId\":\"test-factory\"}"));
        review.decide(HitlReviewStatus.AMENDED, "AMEND", "qa-lead", "tightened", null);
        reviews.save(review);

        // The ALL tab is not the PENDING path: it must render the decided review and
        // the decision columns (only shown when showDecision is true), catching a
        // regression that fell back to the pending-only view.
        String body = assertRendered("/reviews?status=ALL", "Human-in-the-loop gates and their decisions.");
        assertThat(body).contains("Signed-off plan").contains("Decided");
    }

    @Test
    void promptsListShowsReadOnlyView() {
        String body = assertRendered("/prompts", "Bundled prompt templates");
        assertThat(body).contains(">View<").doesNotContain("Overridden");
    }

    @Test
    void statusPageRenders() {
        assertRendered("/status", "Execution engine");
    }

    @Test
    void auditPageRenders() {
        assertRendered("/audit", "Append-only record");
    }

    @Test
    void flowsListRenders() {
        assertRendered("/flows", "Registered flow plugins");
    }

    @Test
    void flowDetailRenders() {
        String body = assertRendered("/flows/test-factory", "Retry policy");
        // The trigger-dialog sample <script> block must hold raw (un-escaped) JSON so the
        // sample-fill helper copies valid text into the textarea. The escaped form only
        // appears in the textarea itself, where the browser decodes it back.
        assertThat(body)
                .contains("type=\"application/json\"")
                .contains("\"issueKey\"");
    }

    @Test
    void workersListRenders() {
        assertRendered("/workers", "agent worker library");
    }

    @Test
    void promptsListRenders() {
        assertRendered("/prompts", "Bundled prompt templates");
    }

    @Test
    void promptViewerRendersActualPromptContent() {
        // Regression: a model attribute named `content` is shadowed by the layout
        // fragment's `content` parameter, which made this page render its own
        // template source instead of the prompt. Only a full render catches that,
        // so assert on the real bundled prompt text.
        String body = assertRendered("/prompts/test-spec-agent?file=user", "Bundled default");
        assertThat(body)
                .contains("Generate the manual test plan")
                .doesNotContain("th:text");
    }

    @Test
    void executionDetailRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        artifactStore.putMarkdown(execution.getId(), "test_plan.md", "# Plan\n", "test-spec");
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "triage", "engine",
                Map.of("promptTokens", 734, "completionTokens", 110));

        String body = assertRendered("/executions/" + execution.getId(), "Audit timeline");
        assertThat(body)
                .contains("LLM tokens: 844 total")
                .contains("844 tokens (734 in / 110 out)")
                .containsOnlyOnce("class=\"stepper\"")
                .doesNotContain("Technical step details", "aria-label=\"Technical steps\"");
    }

    @Test
    void developerRetryReasonReflectsWhetherAutomaticRetryIsPending() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setErrorMessage("Starting build hit a network/download failure");
        executions.save(execution);
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("Retry pending: Starting build hit a network/download failure")
                .doesNotContain("Stop reason: Starting build");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        executions.save(execution);
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("Stop reason: Starting build hit a network/download failure")
                .doesNotContain("Retry pending");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.RUNNING);
        executions.save(execution);
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_starting", "provider", "test-provider", "model", "test-model"));
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .doesNotContain("Starting build hit a network/download failure", "Retry pending", "Stop reason:");
    }

    @Test
    void developerExecutionShowsLiveActivityAndHonestDeliveryOutcome() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{\"issueKey\":\"MODSIDECAR-196\"}"));
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.RUNNING);
        executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_verification.json",
                "{\"result\":\"PASS\",\"testCount\":17,\"exitCode\":0,\"argv\":[\"mvn\",\"test\"]}", "verify");
        artifactStore.putMarkdown(execution.getId(), "dev_readiness.json",
                "{\"state\":\"BASELINE_FAILED\",\"output\":\"[INFO] Compiling 42 source files\\n[ERROR] transfer failed\"}", "implement");
        artifactStore.putMarkdown(execution.getId(), "dev_delivery.json",
                "{\"state\":\"DELIVERY_BLOCKED\",\"reason\":\"Safe destination missing\"}", "publish");
        artifactStore.putMarkdown(execution.getId(), "dev_candidate.json",
                "{\"state\":\"CANDIDATE_UNVERIFIED\",\"patch\":\"SOURCE_CONTENT_SENTINEL\"}", "implement");
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn test"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "turn_start", "message", "HIDDEN_MESSAGE_SENTINEL",
                        "reasoning", "HIDDEN_REASONING_SENTINEL"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", Map.of("costUsd", 0));
        String body = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(body).contains("MODSIDECAR-196", "17 tests executed", "mvn test", "DELIVERY_BLOCKED",
                "Safe destination missing", "class=\"badge badge-outline developer-task-key\">MODSIDECAR-196</span>",
                "auditTrail.length,artifacts.length", "Prepare task", "Implement changes", "Verify changes",
                "Create pull request", "Technical details", "Prepare task checkout", "Starting build output",
                "Compiling 42 source files", "transfer failed", "Artifacts", "Execution history",
                "Open full append-only audit log");
        assertThat(body).containsOnlyOnce("class=\"developer-stages\"")
                .contains("aria-label=\"Technical steps\"", "Implementation", "Not started")
                .doesNotContain("Coding runtime-reported cost", "Cost evidence", "class=\"stepper\"",
                        "SOURCE_CONTENT_SENTINEL", "HIDDEN_MESSAGE_SENTINEL",
                        "HIDDEN_REASONING_SENTINEL", ">Baseline<",
                        "Baseline output tail", "Pinned base SHA", "Run starting build and Pi");
    }

    @Test
    void developerExecutionShowsEstimatedCostEvidenceWithoutRuntimeCost() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_starting", "provider", "test-provider", "model", "test-model"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_usage", "inputTokens", 100_000, "cacheReadTokens", 10_000,
                        "cacheWriteTokens", 0, "outputTokens", 10_000, "costUsd", 0));

        String body = assertRendered("/executions/" + execution.getId(), "Cost evidence");
        assertThat(body)
                .contains("Estimated coding API cost", "0.0203 USD", "using rates dated 2026-09-19",
                        "href=\"https://example.test/pricing\"", ">https://example.test/pricing</a>",
                        "Rate-card estimate; actual provider or proxy charges may differ")
                .doesNotContain("Coding runtime-reported cost");
    }

    @Test
    void developerExecutionExplainsWhyPartialUsageHasNoCostEstimate() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "provider", "test-provider", "model", "test-model"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_usage", "inputTokens", 100_000, "cacheReadTokens", 10_000,
                        "cacheWriteTokens", 0, "outputTokens", 10_000, "usageIncomplete", true));

        String body = assertRendered("/executions/" + execution.getId(), "Usage");
        assertThat(body).contains("Cost estimate unavailable: Pi did not report enough usage for every response.")
                .doesNotContain("Cost evidence", "No matching configured rate card.");
    }

    @Test
    void developerExecutionRendersFinalRetryUsageAndSafeProviderDiagnostics() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_coding_outcome.json",
                "{\"status\":\"FAILED\",\"failure\":{\"code\":\"PROVIDER_RETRIES_EXHAUSTED\","
                        + "\"message\":\"Pi provider request failed: HTTP 429 (rate limited)\"}}", "implement");
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "provider", "test-provider", "model", "test-model"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_usage", "inputTokens", 40_000, "cacheReadTokens", 100_000,
                        "cacheWriteTokens", 0, "outputTokens", 4_000));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "provider_error", "reason", "HTTP 429 (rate limited)",
                        "stopReason", "error"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "auto_retry_start", "attempt", 1, "maxAttempts", 3,
                        "reason", "HTTP 429 (rate limited)"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement",
                Map.of("inputTokens", 100_000, "cacheReadTokens", 200_000, "cacheWriteTokens", 0,
                        "outputTokens", 10_000, "provider", "test-provider", "model", "test-model"));

        String body = assertRendered("/executions/" + execution.getId(), "Provider diagnostics");
        assertThat(body).contains("Stop reason: Pi provider request failed: HTTP 429 (rate limited)",
                        "310k", "0.026 USD", "Coding runtime retrying request (1/3) · HTTP 429 (rate limited)")
                .doesNotContain("Coding runtime-reported cost");
    }

    @Test
    void developerAuditVolumeStaysBehindClosedDebugDisclosure() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution = executions.save(execution);
        for (int i = 0; i < 240; i++)
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "turn_start"));

        String body = assertRendered("/executions/" + execution.getId(), "Execution history (0)");

        assertThat(body)
                .containsOnlyOnce("data-developer-history")
                .containsOnlyOnce("Latest reported activity")
                .contains("Coding agent working", "No meaningful activity reported yet.", "Open full append-only audit log")
                .doesNotContain("Raw audit history", "<details data-developer-history open");
    }

    @Test
    void completedDeveloperExecutionsRenderProductOutcomeInHeaderAndList() {
        for (String outcome : List.of("DEVELOPMENT_FAILED", "DELIVERED")) {
            var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
            execution.setCurrentStepIndex(6);
            execution.setStatus(ExecutionStatus.COMPLETED);
            execution = executions.save(execution);
            artifactStore.putMarkdown(execution.getId(), "dev_result.json",
                    "{\"state\":\"" + outcome + "\"}", "publish");
            if (outcome.equals("DEVELOPMENT_FAILED")) {
                auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                        Map.of("activity", "pi_usage", "inputTokens", 10,
                                "cacheReadTokens", 2, "cacheWriteTokens", 1,
                                "outputTokens", 3, "costUsd", 0.125));
                auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", Map.of());
            }
            String detail = assertRendered("/executions/" + execution.getId(), "Developer Flow");
            assertThat(detail).contains("badge-status-" + outcome, ">" + outcome + "</span>",
                            "Workflow engine status: COMPLETED")
                    .doesNotContain("badge-status-COMPLETED");
            if (outcome.equals("DEVELOPMENT_FAILED"))
                assertThat(detail).contains(">16</strong>", "total tokens", "Input", "Cached read",
                                "Cache write", "Output")
                        .doesNotContain("pi usage", "Coding runtime-reported cost");
            String list = assertRendered("/executions?flow=dev-factory", "Executions");
            assertThat(list).contains("badge-status-" + outcome, ">" + outcome + "</span>")
                    .doesNotContain("badge-status-COMPLETED");
        }
    }

    @Test
    void nonDeveloperCompletedStatusIsUnchangedEvenWithDeveloperNamedArtifact() {
        var execution = new PipelineExecution("test-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_result.json",
                "{\"state\":\"DEVELOPMENT_FAILED\"}", "test");
        assertThat(assertRendered("/executions/" + execution.getId(), "Execution"))
                .contains("badge-status-COMPLETED", ">COMPLETED</span>")
                .doesNotContain("badge-status-DEVELOPMENT_FAILED", "Workflow engine status:");
        assertThat(assertRendered("/executions?flow=test-factory", "Executions"))
                .contains("badge-status-COMPLETED", ">COMPLETED</span>")
                .doesNotContain("badge-status-DEVELOPMENT_FAILED");
    }

    @Test
    void artifactsListAndDetailRender() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        var artifact = artifactStore.putMarkdown(execution.getId(), "coverage_report.md",
                "# Coverage\nAll branches hit.\n", "test-exec");

        String list = assertRendered("/artifacts", "Immutable artifact versions");
        assertThat(list).contains("coverage_report.md").contains("test-factory");

        String detail = assertRendered("/artifacts/" + artifact.getId(), "All branches hit.");
        assertThat(detail).contains("coverage_report.md").contains("test-exec");
    }

    @Test
    void reviewDetailRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        String reviewPackage = "{\"title\":\"Review test plan\",\"flowId\":\"test-factory\","
                + "\"instructions\":\"Please review the plan.\","
                + "\"artifacts\":[{\"name\":\"test_plan.md\",\"version\":1,\"content\":\"# Plan\"}]}";
        HitlReview review = reviews.save(new HitlReview(execution.getId(), "qa-gate-1", 2, reviewPackage));

        assertRendered("/reviews/" + review.getId(), "Instructions");
    }

    @Test
    void stepperAndPaginationFragmentsRender() {
        List<Map<String, Object>> steps = List.of(
                Map.of("stepId", "s1", "type", "AGENT", "label", "Triage",
                        "sublabel", "scope manifest", "state", "done", "attempts", 0),
                Map.of("stepId", "s2", "type", "HITL_GATE", "label", "QA gate",
                        "state", "current", "attempts", 2));
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 10);

        Context context = new Context();
        context.setVariable("steps", steps);
        context.setVariable("page", page);
        context.setVariable("slice", new SliceImpl<>(List.of("a", "b"), PageRequest.of(1, 2), true));
        context.setVariable("baseUrl", "/executions?status=RUNNING");

        String html = templateEngine.process("fragment-smoke", context);

        assertThat(html)
                .contains("class=\"stepper\"")
                .contains("Triage")
                .contains("retry ×2")
                .contains("class=\"pagination\"")
                .contains("&amp;page=0")
                .contains("&amp;page=2")
                // slicePagination: no total count, but prev/next links still render
                .contains("Page 2");
    }

    @Test
    void unknownExecutionRendersHtmlErrorPage() {
        ResponseEntity<String> response = rest.get()
                .uri("/executions/" + UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .contains("app-shell")
                .contains("Not found");
    }

    // Companion to unknownExecutionRendersHtmlErrorPage: the same miss on the REST
    // route must hit ApiExceptionHandler, not the HTML advice — this is the only
    // fully-wired check of the Api-vs-Ui advice precedence.
    @Test
    void unknownExecutionApiReturnsJsonError() {
        ResponseEntity<String> response = rest.get()
                .uri("/api/executions/" + UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("json");
        assertThat(response.getBody()).contains("\"error\"").contains("No execution");
    }

    private String assertRendered(String path, String marker) {
        ResponseEntity<String> response = rest.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("app-shell")
                .contains(marker);
        return response.getBody();
    }
}
