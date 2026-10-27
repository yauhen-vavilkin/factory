package org.folio.factory.app;

import org.folio.factory.app.developer.CodingTranscriptStore;
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
import org.folio.factory.devfactory.runtime.CodingTranscripts;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                "factory.pilot.source.assignee=Jane Developer",
                "factory.developer-pricing.rates.test.model=test-model",
                "factory.developer-pricing.rates.test.currency=USD",
                "factory.developer-pricing.rates.test.as-of=2026-09-19",
                "factory.developer-pricing.rates.test.source=https://example.test/pricing",
                "factory.developer-pricing.rates.test.input-per-million=0.15",
                "factory.developer-pricing.rates.test.cache-read-per-million=0.03",
                "factory.developer-pricing.rates.test.output-per-million=0.50",
                "FACTORY_ZAI_API_KEY=" + UiRenderSmokeTest.ZAI_KEY})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class UiRenderSmokeTest {

    static final String ZAI_KEY = "smoke-zai-secret-key";

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

    @Autowired
    private CodingTranscriptStore transcripts;

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
                .containsPattern("src=\"/js/charts-[0-9a-f]{32}\\.js\"")
                .contains("class=\"charts-grid\"");
        Matcher chart = Pattern.compile("src=\"(/webjars/chart\\.js/[^\"]+/chart\\.umd-[0-9a-f]{32}\\.js)\"")
                .matcher(body);
        assertThat(chart.find()).as("hashed chart.js link").isTrue();
        assertThat(rest.get().uri(chart.group(1)).retrieve().toEntity(String.class).getBody()).contains("Chart");
    }

    @Test
    void pageLinksCarryAContentHashAndHashedFilesAreCachedForAYear() {
        ResponseEntity<String> page = rest.get().uri("/flows/dev-factory").retrieve().toEntity(String.class);
        String body = page.getBody();
        assertThat(page.getHeaders().getCacheControl()).as("HTML pages stay uncached").isNull();
        assertThat(body).doesNotContainPattern("\"/(css|js)/[a-z]+\\.(css|js)\"");
        for (String file : List.of("css/tokens", "css/app", "js/app", "js/theme", "js/confirm", "js/poll")) {
            String extension = file.substring(0, file.indexOf('/'));
            Matcher link = Pattern.compile("\"(/" + file + "-[0-9a-f]{32}\\." + extension + ")\"").matcher(body);
            assertThat(link.find()).as("hashed link for " + file).isTrue();

            ResponseEntity<String> asset = rest.get().uri(link.group(1)).retrieve().toEntity(String.class);
            assertThat(asset.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(asset.getHeaders().getCacheControl()).contains("max-age=31536000", "immutable");
            assertThat(asset.getBody()).isNotBlank();
        }
    }

    @Test
    void unhashedAndUnknownHashUrlsAreNotCachedForAYear() {
        for (String path : List.of("/css/app.css", "/js/poll.js")) {
            ResponseEntity<String> asset = rest.get().uri(path).retrieve().toEntity(String.class);
            assertThat(asset.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(asset.getHeaders().getCacheControl()).as(path).isNull();
        }

        ResponseEntity<String> missing = rest.get().uri("/css/app-" + "0".repeat(32) + ".css").retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getCacheControl()).isNull();
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
                "delivery", "owner/folio-module-sidecar");
        var deliveryCheck = new DeveloperPreflight.Check("delivery:sidecar", deliveryStatus,
                "Push permission check <read-only> & base alignment.");
        var inferenceCheck = new DeveloperPreflight.Check("inference", DeveloperPreflight.Status.NOT_RUN,
                "No model call made.");
        Map<String, Object> profile = Map.ofEntries(Map.entry("id", "glm-5.3"),
                Map.entry("label", "GLM-5.3 (Z.ai)"), Map.entry("default", false), Map.entry("model", "glm-5.3"),
                Map.entry("endpointOrigin", "https://api.z.ai"), Map.entry("authentication", "bearer-token"),
                Map.entry("efforts", List.of("low", "high", "max")), Map.entry("effort", "max"),
                Map.entry("maxTurns", 100), Map.entry("maxOutputTokens", 16384),
                Map.entry("checks", List.of(new DeveloperPreflight.Check("secret", DeveloperPreflight.Status.FAIL,
                        "Profile glm-5.3 is not configured: set FACTORY_ZAI_API_KEY in .env, then run docker"
                                + " compose up -d again."))));
        Map<String, Object> envDefault = Map.ofEntries(Map.entry("id", "env-default"),
                Map.entry("label", "Configured in .env"), Map.entry("default", true),
                Map.entry("model", "claude-sonnet-5-5"), Map.entry("endpointOrigin", "http://host.docker.internal:4001"),
                Map.entry("authentication", "bearer-token"),
                Map.entry("efforts", List.of("low", "medium", "high", "xhigh", "max")),
                Map.entry("effort", "high (CLI default)"), Map.entry("maxTurns", 40),
                Map.entry("maxOutputTokens", 16384), Map.entry("checks", List.of()));
        var report = new DeveloperPreflight.Report(deliveryStatus == DeveloperPreflight.Status.PASS,
                issueKey == null ? "" : issueKey, runtime, List.of(repository), List.of(deliveryCheck, inferenceCheck),
                List.of(envDefault, profile));
        when(developerPreflight.check(issueKey)).thenReturn(report);

        String path = "/developer/configuration";
        if (issueKey != null) {
            path += "?issueKey=" + issueKey;
        }
        String body = assertRendered(path, "Developer Flow configuration");
        assertThat(body).contains("claude-sonnet-5-5", "sidecar", "java21-unit", "owner/folio-module-sidecar",
                        "NOT_RUN", "No model call made.", "Push permission check &lt;read-only&gt; &amp; base alignment.")
                .doesNotContain("check <read-only>");
        assertThat(body).contains("<h2>Run profiles</h2>", "Configured in .env", "env-default · default for runs",
                "Effort high (CLI default)", "low, medium, high, xhigh, max · 40 turns · 16384 output tokens",
                "GLM-5.3 (Z.ai)", "https://api.z.ai · bearer-token", "Effort max",
                "FAIL · secret: Profile glm-5.3 is not configured: set FACTORY_ZAI_API_KEY in .env");
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
            assertThat(body).contains("href=\"" + url + "\">" + label + "</a>", ">Pull request #42</a>")
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
        String activityPanel = body.substring(body.indexOf("<section class=\"run-panel run-activity\""),
                body.indexOf("<section class=\"run-panel run-usage\""));
        assertThat(activityPanel)
                .contains("Runtime log, oldest first · last 20 events",
                        "href=\"/executions/" + execution.getId() + "/transcript\">Transcript</a>",
                        "href=\"/audit?executionId=" + execution.getId() + "\">Audit events</a>",
                        "aria-label=\"Runtime activity, oldest first\"", "Running mvn test")
                .containsPattern("<time title=\"\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\">\\d{2}:\\d{2}:\\d{2}</time>")
                .doesNotContain("newest first", ">full log<");
        assertThat(body).contains("MODSIDECAR-196", "17 tests executed", "mvn test", "DELIVERY_BLOCKED",
                "Safe destination missing", "class=\"task-key\">MODSIDECAR-196</span>",
                "auditTrail.length,artifacts.length", "Prepare task", "Implement changes", "Verify changes",
                "Create pull request", "Technical details", "Prepare task checkout", "Starting build output",
                "Compiling 42 source files", "transfer failed", "Artifacts", "Execution history",
                "Full audit log", "Now · updates live");
        assertThat(body).containsOnlyOnce("class=\"run-stages\"")
                .contains("Engine steps", "Implementation", "Waiting")
                .doesNotContain("class=\"developer-runtime-facts\"", "Coding runtime-reported cost", "Cost evidence", "class=\"stepper\"",
                        "SOURCE_CONTENT_SENTINEL", "HIDDEN_MESSAGE_SENTINEL",
                        "HIDDEN_REASONING_SENTINEL", ">Baseline<",
                        "Baseline output tail", "Pinned base SHA", "Run starting build and Pi");
    }

    @Test
    void transcriptPageShowsEachRunWithACollapsedPromptAndToolCallsWithTheirResults() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        Instant at = Instant.parse("2026-10-27T09:15:30Z");
        try (var sink = transcripts.open(execution.getId(), UUID.randomUUID(), "implement")) {
            sink.add(new CodingTranscripts.Entry("prompt", at, Map.of("text", "Implement MODBAR-1 <now>")));
            sink.add(new CodingTranscripts.Entry("text", at.plusSeconds(1),
                    Map.of("text", "I will run the tests", "thinking", "HIDDEN_REASONING_SENTINEL")));
            sink.add(new CodingTranscripts.Entry("tool_call", at.plusSeconds(2),
                    Map.of("id", "t1", "tool", "Bash", "input", "{ \"command\" : \"mvn test\" }")));
            sink.add(new CodingTranscripts.Entry("tool_result", at.plusSeconds(5),
                    Map.of("id", "t1", "tool", "Bash", "error", true, "durationMs", 3200,
                            "text", "BUILD FAILURE\n[cut: 120 more characters]")));
            sink.add(new CodingTranscripts.Entry("result", at.plusSeconds(6), Map.of("subtype", "success",
                    "error", false, "text", "Done", "turns", 4, "durationMs", 6000,
                    "usage", Map.of("inputTokens", 1200, "outputTokens", 80))));
            sink.add(new CodingTranscripts.Entry("note", at.plusSeconds(7),
                    Map.of("text", "Transcript cut at 5 MB; the attempt went on.")));
        }

        String body = assertRendered("/executions/" + execution.getId() + "/transcript", "Transcript");
        assertThat(body)
                .contains("Run 1 · Implementation", "Started 2026-10-27 09:15:30 UTC",
                        "<summary>Prompt sent to the agent</summary>", "Implement MODBAR-1 &lt;now&gt;",
                        "I will run the tests", "<strong>Bash</strong>", "failed", "3s",
                        "{ &quot;command&quot; : &quot;mvn test&quot; }", "BUILD FAILURE",
                        "[cut: 120 more characters]", "<strong>Final result</strong>",
                        "4 turns · 6s · 1,200 input tokens · 80 output tokens",
                        "Transcript cut at 5 MB; the attempt went on.",
                        "<time title=\"2026-10-27 09:15:32\">09:15:32</time>",
                        "href=\"/executions/" + execution.getId() + "\"")
                .doesNotContain("<details open", "HIDDEN_REASONING_SENTINEL", "<now>");

        var empty = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        assertThat(assertRendered("/executions/" + empty.getId() + "/transcript", "Transcript"))
                .contains("No transcript was stored for this run.");
    }

    @Test
    void codingFailureReasonsReachTheTaskScreensAndTheRunPage() {
        resetPilotData();
        String turnLimit = "Claude Code used all 40 turns before it finished the task."
                + " Retry with more turns or a different effort.";
        PilotTask stopped = seedTask("70081", "MODBAR-781", "Turn limit task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_result.json",
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"" + turnLimit + "\"}", "verify");
        PilotAttempt attempt = seedAttempt(stopped, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.STOPPED,
                "DEVELOPMENT_FAILED: " + turnLimit);
        attempt.setExecutionId(execution.getId());
        pilotAttempts.save(attempt);
        PilotTask provider = seedTask("70082", "MODBAR-782", "Provider task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(provider, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.STOPPED,
                "shared: DEVELOPMENT_FAILED: Claude Code stopped with an error (subtype success, API status 429)."
                        + " Check the provider and the Developer Flow settings, then retry. Error: API Error: 429");

        String expected = "Implementation failed: " + turnLimit.substring(0, turnLimit.length() - 1);
        assertThat(assertRendered("/flows/dev-factory", "My Jira tasks"))
                .contains(expected, "Implementation failed: Claude Code stopped with an error"
                        + " (subtype success, API status 429). Check the provider and the Developer Flow settings,"
                        + " then retry. Error: API Error: 429");
        assertThat(assertRendered("/tasks/" + stopped.getId(), "Could not complete"))
                .contains(expected + ". No pull request was created.");
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("Stop reason: " + turnLimit);
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

        String body = assertRendered("/executions/" + execution.getId(), "<summary>Pricing</summary>");
        String currentPanel = body.substring(body.indexOf("<section class=\"run-panel run-activity\""),
                body.indexOf("<section class=\"run-panel run-usage\""));
        String usagePanel = body.substring(body.indexOf("<section class=\"run-panel run-usage\""),
                body.indexOf("<details class=\"tech-details\""));
        assertThat(currentPanel).doesNotContain("run-facts", "<dt>Model</dt>", "<dt>Provider</dt>",
                "<dt>Runtime</dt>", "<dt>Image</dt>");
        assertThat(usagePanel).contains("Runtime and usage", "<dt>Model</dt>", ">test-model</dd>",
                        "<dt>Provider</dt>", ">test-provider</dd>", "<dt>Runtime</dt>", ">pi</dd>",
                        "<dt>Image</dt>", ">factory-dev-pi:0.85.1-java21</code>",
                        "tokens so far", "Input (uncached)", "Cache read",
                        "Cache write", "Output", ">Estimated cost</dt>", "0.0203 USD")
                .doesNotContain("Configured rate card ·", "Actual provider or proxy charges may differ");
        assertThat(body)
                .containsOnlyOnce("<dt>Model</dt>").containsOnlyOnce("<dt>Provider</dt>")
                .containsOnlyOnce("<dt>Runtime</dt>").containsOnlyOnce("<dt>Image</dt>")
                .contains("0.0203 USD so far", "2026-09-19",
                        "href=\"https://example.test/pricing\"", ">https://example.test/pricing</a>",
                        "Rate-card estimate;")
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
            String currentPanel = body.substring(body.indexOf("<section class=\"run-panel run-activity\""),
                    body.indexOf("<section class=\"run-panel run-usage\""));
            assertThat(currentPanel).contains("class=\"run-log\"", "Starting build started · mvn test");
            assertThat(body).containsOnlyOnce("class=\"run-log\"")
                    .contains("No token usage reported yet.", ">coding-image</code>")
                    .doesNotContain("Latest reported activity", "Recent meaningful activity", "coding configured");
        }
    }

    @Test
    void developerExecutionShowsTheRunProfileModelEffortAndMaxTurns() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "runtime", "claude-code", "provider", "anthropic-messages",
                        "profile", "glm-5.3", "profileLabel", "GLM-5.3 (Z.ai)", "model", "glm-5.3",
                        "effort", "max", "maxTurns", 100));

        String body = assertRendered("/executions/" + execution.getId(), "Runtime and usage");
        String usagePanel = body.substring(body.indexOf("<section class=\"run-panel run-usage\""),
                body.indexOf("<details class=\"tech-details\""));
        assertThat(usagePanel).containsSubsequence("<dt>Profile</dt>", ">GLM-5.3 (Z.ai)<", "<dt>Model</dt>",
                ">glm-5.3<", "<dt>Effort</dt>", ">max<", "<dt>Max turns</dt>", ">100<");
    }

    @Test
    void developerExecutionShowsPartialRuntimeIdentityBeforeUsage() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        for (String field : List.of("model", "provider", "runtime", "image")) {
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "coding_starting", field, "reported-" + field));
            String body = assertRendered("/executions/" + execution.getId(), "Runtime and usage");
            String usagePanel = body.substring(body.indexOf("<section class=\"run-panel run-usage\""),
                    body.indexOf("<details class=\"tech-details\""));
            assertThat(usagePanel).contains("class=\"run-facts\"", ">reported-" + field + "<",
                            "No token usage reported yet.")
                    .doesNotContain("tokens so far", "Estimated cost");
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

        String body = assertRendered("/executions/" + execution.getId(), "Runtime and usage");
        assertThat(body).contains("Cost estimate unavailable: the runtime did not report enough usage for every response.")
                .doesNotContain("<summary>Pricing</summary>", "No matching configured rate card.", ">Estimated cost</dt>");
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
                .containsOnlyOnce("id=\"activity-title\"")
                .contains("No activity reported yet.", "Full audit log")
                .doesNotContain("Raw audit history", "data-developer-history open");
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
                            "<dt>Engine status</dt>", ">COMPLETED</dd>", "id=\"now-title\">Result</h2>")
                    .doesNotContain("badge-status-COMPLETED");
            if (outcome.equals("DEVELOPMENT_FAILED"))
                assertThat(detail).contains(">16</strong>", "<span>tokens</span>", "Input (uncached)",
                                "Cache read", "Cache write", ">Output</dt>")
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
                .doesNotContain("badge-status-DEVELOPMENT_FAILED", "Engine status");
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
        pilotInstallations.save(PilotInstallation.initial());
    }

    private PilotTask seedTask(String issueId, String key, String title,
                               PilotTask.Eligibility eligibility, String routingReason) {
        return seedTask(issueId, key, title, "Open", eligibility, routingReason);
    }

    private PilotTask seedTask(String issueId, String key, String title, String jiraStatus,
                               PilotTask.Eligibility eligibility, String routingReason) {
        return pilotTasks.save(new PilotTask("jira.example.org", issueId, key, title, "MODBAR",
                "{\"key\": \"" + key + "\", \"status\": \"" + jiraStatus + "\"}", "revision-" + issueId,
                eligibility, routingReason));
    }

    private PilotAttempt seedAttempt(PilotTask task, int ordinal, PilotAttempt.Kind kind,
                                     PilotAttempt.State state, String reason) {
        PilotAttempt attempt = new PilotAttempt(task.getId(), ordinal, kind,
                "smoke-" + task.getId() + "-" + ordinal, state);
        attempt.setResultReason(reason);
        return pilotAttempts.save(attempt);
    }

    private void holdTheSlot(PilotAttempt attempt) {
        PilotInstallation installation = pilotInstallations.findById(1).orElseThrow();
        installation.setActiveAttemptId(attempt.getId());
        pilotInstallations.save(installation);
    }

    private PipelineExecution runningExecution() {
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        execution.setStatus(ExecutionStatus.RUNNING);
        return executions.save(execution);
    }

    @Test
    void myTasksRendersAvailableTriggeredAndTheWaitingQueue() {
        resetPilotData();
        seedTask("70001", "MODBAR-701", "Awaiting task", PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedTask("70002", "MODBAR-702", "Unsupported task", PilotTask.Eligibility.INELIGIBLE,
                "Project MODX is not configured: no repository for it in config/developer-repositories.yaml");
        seedTask("70003", "MODBAR-703", "Reviewed task", "In Review", PilotTask.Eligibility.ELIGIBLE,
                "repository mod-bar");
        PilotTask queued = seedTask("70004", "MODBAR-704", "Queued task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(queued, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.RESERVED, null);
        PilotTask stopped = seedTask("70005", "MODBAR-705", "Stopped task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(stopped, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.STOPPED,
                "VERIFICATION_FAILED: 2 tests in error");
        PilotTask delivered = seedTask("70006", "MODBAR-706", "Delivered task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt ready = seedAttempt(delivered, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.PR_READY, null);
        ready.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        pilotAttempts.save(ready);
        PilotTask held = seedTask("70007", "MODBAR-707", "Held task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        holdTheSlot(seedAttempt(held, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.NEEDS_INSPECTION, null));
        PilotTask byKey = seedTask("70008", "MODBAR-708", "Task added by key",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        byKey.setInScope(false);
        pilotTasks.save(byKey);
        PilotAttempt byKeyAttempt = seedAttempt(byKey, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.RESERVED, null);
        byKeyAttempt.setAdmittedVia(PilotAttempt.AdmittedVia.BY_KEY);
        pilotAttempts.save(byKeyAttempt);
        PilotTask live = seedTask("70009", "MODBAR-709", "Live task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = runningExecution();
        PilotAttempt liveAttempt = seedAttempt(live, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        pilotAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", Map.of());

        String body = assertRendered("/flows/dev-factory", "My Jira tasks");
        assertThat(body)
                .contains("aria-current=\"page\">My tasks</a>", "href=\"/flows/dev-factory?view=definition\"")
                .contains("Assigned to", "Jane Developer", "jira.example.org",
                        "assignee = &quot;Jane Developer&quot; AND statusCategory != Done ORDER BY updated DESC",
                        "href=\"https://jira.example.org/issues/?jql=")
                .contains("action=\"/flows/dev-factory/scan\"", "action=\"/tasks/run-selected\"",
                        "action=\"/flows/dev-factory/run-by-key\"", "Not in your list? Run a task by key")
                .contains("data-status-chip=\"Open\"", "data-status-chip=\"In Review\"",
                        "aria-pressed=\"false\"", "data-status=\"In Review\" hidden=\"hidden\"")
                .contains("Not eligible", "Project MODX is not configured")
                .containsPattern("data-available-count>1</span><span class=\"dev-count\"\\s+data-not-eligible>"
                        + " · <span data-not-eligible-count>1</span> not eligible</span>")
                .containsPattern("data-run-selected\\s*>Run</button>")
                .doesNotContainPattern("data-select-all\\s+disabled")
                .contains("href=\"https://jira.example.org/browse/MODBAR-701\"", "href=\"/tasks/")
                .contains("Queued", "next in line", "2nd in line", "Could not complete",
                        "Verification failed: 2 tests in error", "PR ready", "Pull request #7",
                        "href=\"https://github.com/folio-org/mod-bar/pull/7\"", "Needs inspection",
                        "outcome not confirmed", "Working", "Implementation", "added by key")
                .contains("href=\"/executions/" + liveExecution.getId() + "\"")
                .contains("Queue is waiting.", "MODBAR-707 needs inspection", "/mark-stopped",
                        "data-confirm-title=\"Mark MODBAR-707 as stopped?\"", "task-grid-wide")
                // Retry no longer starts a run from the list: it opens the task page form.
                .contains("href=\"/tasks/" + stopped.getId() + "#retry\">Retry</a>", "name=\"returnTo\" value=\"flow\"")
                .doesNotContain(">Retry</button>")
                .contains("Queued tasks are not starting.", "FACTORY_PILOT_EXECUTION_ENABLED=true")
                .contains("data-task-selection", "data-run-selected", "data-selected-count",
                        "data-select-all", "data-poll-notice", "data-poll-url=\"/tasks/poll\"",
                        "data-poll-fields=\"revision\"", "data-poll-baseline=")
                .doesNotContain("Trigger flow", "Outside current filter", "Enable automatic admission",
                        "Withdrawn", "Sprint");
        // Run and Run by key share one compact Profile, Effort and Max turns row each.
        String run = body.substring(body.indexOf("action=\"/tasks/run-selected\""), body.indexOf("data-run-selected"));
        String keyForm = body.substring(body.indexOf("action=\"/flows/dev-factory/run-by-key\""),
                body.indexOf("Add to run</button>"));
        for (String form : List.of(run, keyForm)) {
            assertThat(form).contains("Profile", "name=\"profileId\"", "data-run-profile", "Effort", "name=\"effort\"",
                            "data-run-effort", "Max turns", "name=\"maxTurns\"", "max=\"500\"", "value=\"40\"")
                    .containsPattern("<option value=\"env-default\"[^>]*selected=\"selected\"[^>]*>\\s*Configured in \\.env")
                    .containsPattern("<option value=\"glm-5.3\"(?=[^>]*data-default-effort=\"max\")"
                            + "(?=[^>]*data-max-turns=\"100\")[^>]*>GLM-5.3 \\(Z\\.ai\\)")
                    .containsPattern("<option value=\"\"(?=[^>]*data-profile=\"env-default\")(?=[^>]*selected=\"selected\")"
                            + "[^>]*>high \\(CLI default\\)")
                    .containsPattern("<option value=\"xhigh\"[^>]*data-profile=\"env-default\"")
                    // Another profile's efforts are hidden and disabled until the script shows them.
                    .containsPattern("<option value=\"max\" data-profile=\"glm-5.3\"(?=[^>]*hidden=\"hidden\")"
                            + "(?=[^>]*disabled=\"disabled\")[^>]*>max");
        }
        assertThat(body).doesNotContain(ZAI_KEY, "not configured)");
        assertThat(rest.get().uri("/tasks").retrieve().toEntity(String.class).getBody()).contains("My Jira tasks");
    }

    @Test
    void myTasksDisablesRunWhenNoShownTaskCanRun() {
        resetPilotData();
        for (int i = 1; i <= 3; i++) {
            seedTask("7010" + i, "MODBAR-71" + i, "Unsupported task " + i, PilotTask.Eligibility.INELIGIBLE,
                    "Project MODX is not configured: no repository for it in config/developer-repositories.yaml");
        }
        seedTask("70104", "MODBAR-714", "Hidden eligible task", "In Review", PilotTask.Eligibility.ELIGIBLE,
                "repository mod-bar");

        String body = assertRendered("/flows/dev-factory", "My Jira tasks");
        assertThat(body)
                .containsPattern("data-available-count>0</span><span class=\"dev-count\"\\s+data-not-eligible>"
                        + " · <span data-not-eligible-count>3</span> not eligible</span>")
                .containsPattern("data-run-selected\\s+disabled=\"disabled\">Run</button>")
                .containsPattern("data-select-all\\s+disabled=\"disabled\"");
    }

    @Test
    void myTasksShowsEmptyStatesAndTheLastScanProblem() {
        resetPilotData();

        String body = assertRendered("/flows/dev-factory", "Press Scan Jira to load the tasks assigned to you.");
        assertThat(body).contains("Not scanned yet", "Nothing has run yet.");

        PilotInstallation installation = pilotInstallations.findById(1).orElseThrow();
        installation.setSyncProblem("search page 2 failed");
        pilotInstallations.save(installation);
        assertThat(assertRendered("/flows/dev-factory", "Last scan failed: search page 2 failed"))
                .contains("My Jira tasks");
    }

    @Test
    void developerFlowDefinitionTabHasNoJsonTrigger() {
        String body = assertRendered("/flows/dev-factory?view=definition", "Retry policy");
        assertThat(body).contains("aria-current=\"page\">Definition</a>", "Check configuration")
                .doesNotContain("trigger-dialog", "Trigger flow");
        assertThat(assertRendered("/flows/test-factory", "Retry policy")).contains("Trigger flow");
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

        String list = assertRendered("/flows/dev-factory", "Fix &lt;routing&gt; &amp; permissions");
        assertThat(list).doesNotContain("<script>alert(1)</script>").contains(longTitle);

        String detail = assertRendered("/tasks/" + hostile.getId(), "Fix &lt;routing&gt; &amp; permissions");
        assertThat(detail).doesNotContain("<script>alert(1)</script>").contains("MODBAR-711", "Not started");

        assertThat(assertRendered("/tasks/" + longTitled.getId(), longTitle)).contains(longTitle);
    }

    @Test
    void taskSummaryShowsResultCostAttemptsAndRetry() {
        resetPilotData();
        PilotTask stopped = seedTask("70021", "MODBAR-721", "Stopped task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PilotAttempt attempt = seedAttempt(stopped, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.STOPPED, "VERIFICATION_FAILED: 2 tests in error");
        attempt.setExecutionId(execution.getId());
        attempt.setRunSettings("{\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\",\"model\":\"glm-5.3\","
                + "\"endpointOrigin\":\"https://api.z.ai\",\"effort\":\"low\",\"maxTurns\":77,"
                + "\"instructions\":\"Read the ADR through the wiki <REST> URL.\"}");
        pilotAttempts.save(attempt);
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "model", "test-model",
                        "inputTokens", 100_000, "cacheReadTokens", 10_000, "cacheWriteTokens", 0,
                        "outputTokens", 10_000,
                        "rate", Map.of("currency", "USD", "source", "card",
                                "inputPerMillion", 0.15, "cacheReadPerMillion", 0.03, "outputPerMillion", 0.5)));

        String body = assertRendered("/tasks/" + stopped.getId(), "Could not complete");
        assertThat(body)
                .contains("← Developer Flow · My tasks",
                        "Verification failed: 2 tests in error. No pull request was created.")
                .contains("$0.02 total", "1 attempt", "Cost and usage", "120,000", "Input (uncached)", "Output",
                        ">Estimated cost</dt>")
                .contains(">Retry</button>", "name=\"expectedVersion\"", "name=\"inputFingerprint\"",
                        "name=\"destinationFingerprint\"", "Retry starts one fresh attempt")
                // Retry is a form in the Result card, filled from the previous attempt.
                .containsPattern("<form class=\"retry-form\" id=\"retry\" method=\"post\"\\s+action=\"/tasks/"
                        + stopped.getId() + "/retry\"")
                .contains("Instructions for the agent", "name=\"instructions\"", "maxlength=\"8000\"",
                        ">Read the ADR through the wiki &lt;REST&gt; URL.</textarea>", "value=\"77\"")
                .containsPattern("<option value=\"glm-5.3\"[^>]*selected=\"selected\"")
                .containsPattern("<option value=\"low\"(?=[^>]*data-profile=\"glm-5.3\")(?=[^>]*selected=\"selected\")")
                .contains("<span class=\"attempt-run\">glm-5.3 · low</span>")
                .doesNotContain(ZAI_KEY)
                .contains("href=\"/executions/" + execution.getId() + "\">Open last run</a>",
                        "1 · first run", "Technical details", stopped.getId().toString());

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

        assertThat(assertRendered("/tasks/" + partial.getId(), "total · incomplete"))
                .contains("Incomplete: some usage has no reported tokens or rate yet.", "50,000");

        PilotTask live = seedTask("70023", "MODBAR-723", "Live task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = runningExecution();
        PilotAttempt liveAttempt = seedAttempt(live, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        pilotAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "activity", "coding_usage", "model", "test-model",
                        "inputTokens", 10_000, "cacheReadTokens", 0, "cacheWriteTokens", 0,
                        "rate", Map.of("currency", "USD", "inputPerMillion", 0.15)));
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", "smoke", Map.of());

        assertThat(assertRendered("/tasks/" + live.getId(), "In progress: Implementation."))
                .contains("Working", "Open live run", "Includes live usage that can still change.")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void taskSummaryForPrReadyLinksThePullRequestAndItsFeedback() {
        resetPilotData();
        PilotTask delivered = seedTask("70031", "MODBAR-731", "Delivered task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PilotAttempt attempt = seedAttempt(delivered, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        attempt.setExecutionId(execution.getId());
        pilotAttempts.save(attempt);

        String body = assertRendered("/tasks/" + delivered.getId(), "Pull request #7 is ready for review.");
        assertThat(body)
                .contains("href=\"https://github.com/folio-org/mod-bar/pull/7\">Open pull request #7</a>",
                        "href=\"/executions/" + execution.getId() + "#pr-feedback\">Work on PR feedback</a>",
                        "state-pill-success")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void taskSummaryOffersMarkAsStoppedOnlyForAHeldTask() {
        resetPilotData();
        PilotTask held = seedTask("70061", "MODBAR-761", "Held task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PilotAttempt heldAttempt = seedAttempt(held, 1, PilotAttempt.Kind.INITIAL,
                PilotAttempt.State.NEEDS_INSPECTION, null);
        holdTheSlot(heldAttempt);

        assertThat(assertRendered("/tasks/" + held.getId(), "Needs inspection"))
                .contains("Factory could not confirm how the last run ended.", "Mark as stopped…",
                        "/attempts/" + heldAttempt.getId() + "/mark-stopped", "data-confirm-style=\"compact\"")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void taskSummaryRendersFullAttemptHistoryNewestFirst() {
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

        String body = assertRendered("/tasks/" + retried.getId(), "Attempts");
        assertThat(body)
                .contains("3 · retry", "2 · retry", "1 · first run", "Withdrawn before start",
                        "Verification failed: first failure", "Verification failed: second failure")
                .contains("href=\"/executions/" + first.getId() + "\"", "href=\"/executions/" + second.getId() + "\"")
                .contains("Could not complete");
        assertThat(body.indexOf("3 · retry")).isLessThan(body.indexOf("1 · first run"));
    }

    @Test
    void runPageLinksBackToItsTaskAndShowsTheAttemptState() {
        resetPilotData();
        PilotTask task = seedTask("70071", "MODBAR-771", "Running task",
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = runningExecution();
        PilotAttempt attempt = seedAttempt(task, 1, PilotAttempt.Kind.INITIAL, PilotAttempt.State.RUNNING, null);
        attempt.setExecutionId(execution.getId());
        pilotAttempts.save(attempt);

        String body = assertRendered("/executions/" + execution.getId(), "Now · updates live");
        assertThat(body)
                .contains(">Running task</h1>", "href=\"/flows/dev-factory\">Developer Flow</a>",
                        "href=\"/tasks/" + task.getId() + "\">MODBAR-771</a>",
                        "href=\"https://jira.example.org/browse/MODBAR-771\"",
                        "class=\"state-pill\">Working</span>", "Runtime and usage", "Engine steps")
                .doesNotContain("badge-status-RUNNING");
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
