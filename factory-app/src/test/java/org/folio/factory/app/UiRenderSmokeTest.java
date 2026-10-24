package org.folio.factory.app;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.Mockito.when;

/**
 * The only test that actually renders the Thymeleaf templates end to end;
 * standalone MockMvc controller tests never resolve the layout/component
 * fragments. Boots the app, seeds one execution + review, and asserts every
 * UI page renders through the shared layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
                "factory.connectors.jira.base-url=https://jira.example.org",
                "factory.developer-pricing.rates.test.model=test-model",
                "factory.developer-pricing.rates.test.currency=USD",
                "factory.developer-pricing.rates.test.as-of=2026-09-19",
                "factory.developer-pricing.rates.test.source=https://example.test/pricing",
                "factory.developer-pricing.rates.test.input-per-million=0.15",
                "factory.developer-pricing.rates.test.cache-read-per-million=0.03",
                "factory.developer-pricing.rates.test.output-per-million=0.50"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
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
    private PilotTaskRepository pilotTasks;

    @Autowired
    private PilotAttemptRepository pilotAttempts;

    @Autowired
    private PilotInstallationRepository pilotInstallations;

    @Autowired
    private ITemplateEngine templateEngine;

    @MockitoBean
    private DeveloperPreflight developerPreflight;

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
    void developerConfigurationOverviewRequestsAnIssueKey() {
        String body = renderDeveloperConfiguration(null, DeveloperPreflight.Status.NOT_RUN);

        assertThat(body).contains("Configuration overview. Enter an issue key for task readiness.")
                .doesNotContain("Ready for the requested task.");
    }

    @Test
    void blockedDeveloperConfigurationExplainsReadinessFailure() {
        String body = renderDeveloperConfiguration("MODSIDECAR-196", DeveloperPreflight.Status.FAIL);

        assertThat(body).contains("Resolve failed checks before triggering this task.")
                .doesNotContain("Ready for the requested task.");
    }

    @Test
    void readyDeveloperConfigurationKeepsProviderCoverageExplicit() {
        String body = renderDeveloperConfiguration("MODSIDECAR-196", DeveloperPreflight.Status.PASS);

        assertThat(body).contains("Ready for the requested task.",
                        "Provider inference and coding-container connectivity remain untested.")
                .doesNotContain("Resolve failed checks", "Configuration overview.");
    }

    private String renderDeveloperConfiguration(String issueKey, DeveloperPreflight.Status deliveryStatus) {
        Map<String, Object> runtime = Map.of(
                "runtime", "claude-code",
                "model", "claude-sonnet-5-5",
                "image", "factory-dev-claude-code:2.1.287-java21");
        Map<String, Object> repository = Map.of(
                "key", "sidecar",
                "source", "owner/folio-module-sidecar",
                "baseBranch", "master",
                "buildImage", "maven:3.9-eclipse-temurin-21",
                "verificationPlan", "java21-unit",
                "jiraProjects", List.of("MODSIDECAR"),
                "delivery", "owner/folio-module-sidecar",
                "authorized", true);
        var deliveryCheck = new DeveloperPreflight.Check("delivery:sidecar", deliveryStatus,
                "Push permission check <read-only> & base alignment.");
        var inferenceCheck = new DeveloperPreflight.Check("inference", DeveloperPreflight.Status.NOT_RUN,
                "No model call made.");
        var report = new DeveloperPreflight.Report(deliveryStatus == DeveloperPreflight.Status.PASS,
                issueKey == null ? "" : issueKey, runtime, List.of(repository), List.of(deliveryCheck, inferenceCheck));
        when(developerPreflight.check(issueKey)).thenReturn(report);

        String path = "/developer/configuration";
        if (issueKey != null) {
            path += "?issueKey=" + issueKey;
        }
        String body = assertRendered(path, "Developer Flow configuration");
        assertThat(body).contains("claude-sonnet-5-5", "sidecar", "java21-unit", "owner/folio-module-sidecar",
                        "NOT_RUN", "No model call made.", "Push permission check &lt;read-only&gt; &amp; base alignment.")
                .doesNotContain("check <read-only>");
        return body;
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
    void developerExecutionShowsPullRequestTitleOrUrlAsLink() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        String url = "https://github.com/user/repo/pull/42";
        for (String title : List.of("", "TASK-1: Fix <routing> & permissions")) {
            artifactStore.putMarkdown(execution.getId(), "dev_delivery.json",
                    "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"" + url
                            + "\",\"pullRequestTitle\":\"" + title + "\"}", "publish");
            String body = assertRendered("/executions/" + execution.getId(), "Pull request");
            String label = title.isEmpty() ? url : "TASK-1: Fix &lt;routing&gt; &amp; permissions";
            assertThat(body).contains("class=\"developer-pr-link\"", "href=\"" + url + "\">" + label + "</a>")
                    .doesNotContain("Open pull request", "<routing>");
        }
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
                .doesNotContain("class=\"developer-runtime-facts\"", "Coding runtime-reported cost", "Cost evidence", "class=\"stepper\"",
                        "SOURCE_CONTENT_SENTINEL", "HIDDEN_MESSAGE_SENTINEL",
                        "HIDDEN_REASONING_SENTINEL", ">Baseline<",
                        "Baseline output tail", "Pinned base SHA", "Run starting build and Pi");
    }

    @Test
    void developerExecutionShowsEstimatedCostEvidenceWithoutRuntimeCost() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "runtime", "pi", "provider", "test-provider", "model", "test-model",
                        "image", "factory-dev-pi:0.85.1-java21"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_usage", "inputTokens", 100_000, "cacheReadTokens", 10_000,
                        "cacheWriteTokens", 0, "outputTokens", 10_000, "costUsd", 0));

        String body = assertRendered("/executions/" + execution.getId(), "Cost evidence");
        String currentPanel = body.substring(body.indexOf("<section class=\"developer-panel developer-current\""),
                body.indexOf("<section class=\"developer-panel developer-usage\""));
        String usagePanel = body.substring(body.indexOf("<section class=\"developer-panel developer-usage\""),
                body.indexOf("<div class=\"developer-debug\""));
        assertThat(currentPanel).doesNotContain("developer-runtime-facts", "<dt>Model</dt>", "<dt>Provider</dt>",
                "<dt>Runtime</dt>", "<dt>Image</dt>");
        assertThat(usagePanel).contains("Runtime &amp; usage", "<dt>Model</dt>", ">test-model</dd>",
                        "<dt>Provider</dt>", ">test-provider</dd>", "<dt>Runtime</dt>", ">pi</dd>",
                        "<dt>Image</dt>", ">factory-dev-pi:0.85.1-java21</dd>",
                        "total usage tokens", "Input tokens (uncached)", "Cache read tokens",
                        "Cache creation tokens", "Output tokens", "<dt>Estimated cost</dt>", "0.0203 USD")
                .doesNotContain("Configured rate card ·", "Actual provider or proxy charges may differ");
        assertThat(body)
                .containsOnlyOnce("<dt>Model</dt>").containsOnlyOnce("<dt>Provider</dt>")
                .containsOnlyOnce("<dt>Runtime</dt>").containsOnlyOnce("<dt>Image</dt>")
                .contains("Estimated coding API cost", "0.0203 USD", "using rates dated 2026-09-19",
                        "href=\"https://example.test/pricing\"", ">https://example.test/pricing</a>",
                        "Rate-card estimate; actual provider or proxy charges may differ")
                .doesNotContain("Coding runtime-reported cost", "<dt>Coding runtime</dt>", "<dt>Coding image</dt>");
    }

    @Test
    void developerExecutionShowsConfiguredModelAcrossStagesAndActivityInLeftPanel() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.8.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "read-task",
                Map.of("activity", "coding_configured", "model", "planned-model", "provider", "test-provider",
                        "runtime", "pi", "image", "coding-image"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "baseline_started", "message", "mvn test"));
        for (int stage : List.of(0, 3, 6, 7)) {
            execution.setCurrentStepIndex(stage);
            execution.setStatus(ExecutionStatus.RUNNING);
            execution = executions.save(execution);
            String body = assertRendered("/executions/" + execution.getId(), "planned-model");
            String currentPanel = body.substring(body.indexOf("<section class=\"developer-panel developer-current\""),
                    body.indexOf("<section class=\"developer-panel developer-usage\""));
            assertThat(currentPanel).contains("class=\"developer-recent\"", "Starting build started · mvn test");
            assertThat(body).containsOnlyOnce("class=\"developer-recent\"")
                    .contains("No token usage reported yet.", ">coding-image</dd>")
                    .doesNotContain("Latest reported activity", "Recent meaningful activity", "coding configured");
        }
    }

    @Test
    void developerExecutionShowsPartialRuntimeIdentityBeforeUsage() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        for (String field : List.of("model", "provider", "runtime", "image")) {
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "coding_starting", field, "reported-" + field));
            String body = assertRendered("/executions/" + execution.getId(), "Runtime &amp; usage");
            String usagePanel = body.substring(body.indexOf("<section class=\"developer-panel developer-usage\""),
                    body.indexOf("<div class=\"developer-debug\""));
            assertThat(usagePanel).contains("class=\"developer-runtime-facts\"", ">reported-" + field + "</dd>",
                            "No token usage reported yet.")
                    .doesNotContain("total usage tokens", "<dt>Estimated cost</dt>");
            for (String absent : List.of("model", "provider", "runtime", "image")) {
                if (!absent.equals(field))
                    assertThat(usagePanel).doesNotContain("<dt>" + Character.toUpperCase(absent.charAt(0))
                            + absent.substring(1) + "</dt>");
            }
        }
    }

    @Test
    void developerExecutionExplainsWhyPartialUsageHasNoCostEstimate() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "provider", "test-provider", "model", "test-model"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_usage", "inputTokens", 100_000, "cacheReadTokens", 10_000,
                        "cacheWriteTokens", 0, "outputTokens", 10_000, "usageIncomplete", true));

        String body = assertRendered("/executions/" + execution.getId(), "Runtime &amp; usage");
        assertThat(body).contains("Cost estimate unavailable: Pi did not report enough usage for every response.")
                .doesNotContain("Cost evidence", "No matching configured rate card.", "<dt>Estimated cost</dt>");
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
                .containsOnlyOnce("id=\"developer-recent-heading\"")
                .contains("No activity reported yet.", "Open full append-only audit log")
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
                assertThat(detail).contains(">16</strong>", "total usage tokens", "Input tokens (uncached)",
                                "Cache read tokens", "Cache creation tokens", "Output tokens")
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

    // ----- pilot task screens -----

    private void resetPilotData() {
        pilotAttempts.deleteAll();
        pilotTasks.deleteAll();
        pilotInstallations.deleteAll();
    }

    private PilotTask seedTask(String issueId, String key, String title,
                               PilotTask.Eligibility eligibility, String routingReason) {
        return pilotTasks.save(new PilotTask("jira.example.org", issueId, key, title, "MODBAR",
                "{\"key\": \"" + key + "\"}", "revision-" + issueId, eligibility, routingReason));
    }

    private PilotAttempt seedAttempt(PilotTask task, int ordinal, PilotAttempt.Kind kind,
                                     PilotAttempt.State state, String reason) {
        PilotAttempt attempt = new PilotAttempt(task.getId(), ordinal, kind,
                "smoke-" + task.getId() + "-" + ordinal, state);
        attempt.setResultReason(reason);
        return pilotAttempts.save(attempt);
    }

    private void seedSync(boolean complete, String problem) {
        PilotInstallation installation = pilotInstallations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(complete);
        installation.setSyncProblem(problem);
        installation.setObservedAuthorityFingerprint(complete ? "authority-1" : null);
        pilotInstallations.save(installation);
    }

    @Test
    void pilotTaskListRendersEveryStateWithReadableControls() {
        resetPilotData();
        seedTask("70001", "MODBAR-701", "Awaiting task", PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedTask("70002", "MODBAR-702", "Unsupported task", PilotTask.Eligibility.INELIGIBLE,
                "no repository mapping for project MODX");
        PilotTask queued = seedTask("70003", "MODBAR-703", "Queued task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(queued, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.RESERVED, null);
        PilotTask stopped = seedTask("70004", "MODBAR-704", "Stopped task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(stopped, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.STOPPED,
                "verification: 2 tests failed");
        PilotTask delivered = seedTask("70005", "MODBAR-705", "Delivered task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt ready = seedAttempt(delivered, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.PR_READY, null);
        ready.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        pilotAttempts.save(ready);
        PilotTask withdrawn = seedTask("70006", "MODBAR-706", "Withdrawn task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(withdrawn, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.WITHDRAWN,
                "task left the pilot scope before start");
        PilotTask uncertain = seedTask("70007", "MODBAR-707", "Uncertain task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(uncertain, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");
        PilotTask paused = seedTask("70008", "MODBAR-708", "Paused task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt pausedAttempt = seedAttempt(paused, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.NEEDS_INSPECTION, null);
        PilotInstallation installation =
                pilotInstallations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setActiveAttemptId(pausedAttempt.getId());
        pilotInstallations.save(installation);
        PilotTask left = seedTask("70009", "MODBAR-709", "Left task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        left.setInScope(false);
        pilotTasks.save(left);
        seedAttempt(left, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.STOPPED,
                "coding: verification failed");
        PilotTask live = seedTask("70010", "MODBAR-710", "Live task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        liveExecution.setStatus(ExecutionStatus.RUNNING);
        executions.save(liveExecution);
        PilotAttempt liveAttempt = seedAttempt(live, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        pilotAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", Map.of());

        String body = assertRendered("/tasks", "Discovered Jira work");
        assertThat(body)
                .contains("Awaiting selection", "Not eligible", "Queued", "Could not complete",
                        "Withdrawn", "Needs inspection", "Paused", "PR ready")
                .contains("Implementation", "Verification")
                .contains("href=\"https://jira.example.org/browse/MODBAR-701\"")
                .contains("href=\"https://github.com/folio-org/mod-bar/pull/7\"")
                .contains("href=\"/tasks/")
                .contains("verification: 2 tests failed")
                .contains("Outside current filter")
                .contains("Review before run")
                .contains("Available after discovery completes a full sync.")
                .contains("name=\"expectedAuthorityFingerprint\"")
                .contains("data-task-selection", "data-run-selected", "data-selected-count",
                        "data-select-all", "data-poll-notice")
                .contains("data-poll-url=\"/tasks/poll\"")
                .contains("data-poll-fields=\"revision\"")
                .contains("data-poll-baseline=")
                .contains("Run selected")
                .doesNotContain("Delegates every eligible current and future match");
    }

    @Test
    void pilotTaskScreensEscapeIssueText() {
        resetPilotData();
        PilotTask hostile = seedTask("70011", "MODBAR-711",
                "Fix <routing> & permissions <script>alert(1)</script>",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        String longTitle = "Modular routing regression " + "x".repeat(300);
        PilotTask longTitled = seedTask("70012", "MODBAR-712", longTitle,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");

        String list = assertRendered("/tasks", "Fix &lt;routing&gt; &amp; permissions");
        assertThat(list).doesNotContain("<script>alert(1)</script>").contains(longTitle);

        String detail = assertRendered("/tasks/" + hostile.getId(), "Fix &lt;routing&gt; &amp; permissions");
        assertThat(detail).doesNotContain("<script>alert(1)</script>").contains("MODBAR-711", "Awaiting selection");

        assertThat(assertRendered("/tasks/" + longTitled.getId(), longTitle)).contains(longTitle);
    }

    @Test
    void pilotTaskListWithoutTasksShowsEmptyState() {
        resetPilotData();

        String body = assertRendered("/tasks", "No tasks discovered yet.");
        assertThat(body).contains("Review before run", "0 selected");
    }

    @Test
    void pilotTaskDetailShowsResultCostAndRetry() {
        resetPilotData();
        PilotTask stopped = seedTask("70021", "MODBAR-721", "Stopped task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PilotAttempt attempt = seedAttempt(stopped, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.STOPPED, "verification: 2 tests failed");
        attempt.setExecutionId(execution.getId());
        pilotAttempts.save(attempt);
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "model", "test-model",
                        "inputTokens", 100_000, "cacheReadTokens", 10_000, "cacheWriteTokens", 0,
                        "outputTokens", 10_000,
                        "rate", Map.of("currency", "USD", "source", "card",
                                "inputPerMillion", 0.15, "cacheReadPerMillion", 0.03, "outputPerMillion", 0.5)));

        String body = assertRendered("/tasks/" + stopped.getId(), "Could not complete");
        assertThat(body)
                .contains("verification: 2 tests failed")
                .contains("Verification")
                .contains("0.0203 USD", "120,000", "Input tokens", "Output tokens")
                .contains("Retry")
                .contains("name=\"expectedVersion\"", "name=\"inputFingerprint\"",
                        "name=\"destinationFingerprint\"")
                .contains("/executions/" + execution.getId())
                .contains(stopped.getId().toString())
                .contains("All tasks");

        PilotTask partial = seedTask("70022", "MODBAR-722", "Partial task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution partialExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PilotAttempt partialAttempt = seedAttempt(partial, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.STOPPED, "verification: incomplete");
        partialAttempt.setExecutionId(partialExecution.getId());
        pilotAttempts.save(partialAttempt);
        auditLog.record(partialExecution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "model", "test-model",
                        "inputTokens", 50_000, "cacheReadTokens", 0, "cacheWriteTokens", 0,
                        "rate", Map.of("currency", "USD", "inputPerMillion", 0.15)));

        String partialBody = assertRendered("/tasks/" + partial.getId(), "incomplete estimate");
        assertThat(partialBody).contains("0.0075 USD", "50,000");

        PilotTask live = seedTask("70023", "MODBAR-723", "Live task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        liveExecution.setStatus(ExecutionStatus.RUNNING);
        executions.save(liveExecution);
        PilotAttempt liveAttempt = seedAttempt(live, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        pilotAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "activity", "coding_usage", "model", "test-model",
                        "inputTokens", 10_000, "cacheReadTokens", 0, "cacheWriteTokens", 0,
                        "rate", Map.of("currency", "USD", "inputPerMillion", 0.15)));
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", "smoke", Map.of());

        String liveBody = assertRendered("/tasks/" + live.getId(), "provisional estimate");
        assertThat(liveBody).contains("Working", "Implementation", "Retry is unavailable");
    }

    @Test
    void pilotTaskDetailPrReadyHidesRetryAndExplainsWhy() {
        resetPilotData();
        PilotTask delivered = seedTask("70031", "MODBAR-731", "Delivered task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt attempt = seedAttempt(delivered, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        pilotAttempts.save(attempt);

        String body = assertRendered("/tasks/" + delivered.getId(), "PR ready");
        assertThat(body)
                .contains("https://github.com/folio-org/mod-bar/pull/7", "under human review")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void pilotTaskDetailExplainsWhyRetryIsUnavailableForHeldAndWithdrawnTasks() {
        resetPilotData();
        PilotTask paused = seedTask("70061", "MODBAR-761", "Paused task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt pausedAttempt = seedAttempt(paused, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.NEEDS_INSPECTION, null);
        PilotInstallation installation =
                pilotInstallations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setActiveAttemptId(pausedAttempt.getId());
        pilotInstallations.save(installation);
        PilotTask uncertain = seedTask("70062", "MODBAR-762", "Uncertain task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(uncertain, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");
        PilotTask withdrawn = seedTask("70063", "MODBAR-763", "Withdrawn task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(withdrawn, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.WITHDRAWN,
                "task left the pilot scope before start");

        assertThat(assertRendered("/tasks/" + paused.getId(), "Paused"))
                .contains("Retry is unavailable until the held attempt is resolved.")
                .doesNotContain("name=\"expectedVersion\"");
        assertThat(assertRendered("/tasks/" + uncertain.getId(), "Needs inspection"))
                .contains("Retry is unavailable until the held attempt is resolved.");
        assertThat(assertRendered("/tasks/" + withdrawn.getId(), "Withdrawn"))
                .contains("The admission was withdrawn before start; select the task again.")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void pilotTaskDetailRendersFullAttemptHistory() {
        resetPilotData();
        PilotTask retried = seedTask("70041", "MODBAR-741", "Retried task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution first = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PipelineExecution second = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        seedAttempt(retried, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.WITHDRAWN,
                "task left the pilot scope before start");
        PilotAttempt retry = seedAttempt(retried, 2, PilotAttempt.Kind.RETRY,
                PilotAttempt.State.STOPPED, "verification: first failure");
        retry.setExecutionId(first.getId());
        pilotAttempts.save(retry);
        PilotAttempt fresh = seedAttempt(retried, 3, PilotAttempt.Kind.RETRY,
                PilotAttempt.State.STOPPED, "verification: second failure");
        fresh.setExecutionId(second.getId());
        pilotAttempts.save(fresh);

        String body = assertRendered("/tasks/" + retried.getId(), "Attempt 3");
        assertThat(body)
                .contains("Initial attempt", "Retry")
                .contains("task left the pilot scope before start",
                        "verification: first failure", "verification: second failure")
                .contains("/executions/" + first.getId(), "/executions/" + second.getId())
                .contains("Could not complete");
    }

    @Test
    void pilotTaskListOffersAutomaticAdmissionAfterCompleteSync() {
        resetPilotData();
        seedSync(true, null);
        seedTask("70051", "MODBAR-751", "Awaiting task", PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");

        String body = assertRendered("/tasks", "Enable automatic admission");
        assertThat(body)
                .contains("value=\"authority-1\"")
                .contains("Delegates every eligible current and future match")
                .doesNotContain("Available after discovery completes a full sync.");
    }

    @Test
    void pilotTaskListExplainsSyncProblem() {
        resetPilotData();
        seedSync(false, "search page 2 failed");

        String body = assertRendered("/tasks", "Discovery has not completed a full sync");
        assertThat(body).contains("search page 2 failed");
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
