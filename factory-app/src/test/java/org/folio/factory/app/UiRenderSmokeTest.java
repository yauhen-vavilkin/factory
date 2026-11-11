package org.folio.factory.app;

import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.developer.ModelChecks;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.tasks.TaskFixture;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.web.PageTime;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * The only test that actually renders the Thymeleaf templates end to end;
 * standalone MockMvc controller tests never resolve the layout/component
 * fragments. Boots the app, seeds one execution + review, and asserts every
 * UI page renders through the shared layout.
 */
@FactoryIntegrationTest
@TestPropertySource(properties = {
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.tasks.source.assignee=Jane Developer",
        "FACTORY_CODEMIE_PROXY_TOKEN=" + UiRenderSmokeTest.PROXY_TOKEN,
        "FACTORY_ANTHROPIC_API_KEY=",
        "FACTORY_ZAI_API_KEY=" + UiRenderSmokeTest.ZAI_KEY})
// Own context: its own Jira, assignee and model secrets for the rendered pages; closing it stops its schedulers and frees its connections.
@DirtiesContext
class UiRenderSmokeTest {

    static final String ZAI_KEY = "smoke-zai-secret-key";
    static final String PROXY_TOKEN = "smoke-proxy-secret-token";
    private static final String LUNA_FIX = "This model uses the OpenAI Responses API; Claude Code needs the"
            + " Anthropic Messages API. Point it at an Anthropic-compatible endpoint, or run it with Pi: set"
            + " FACTORY_DEV_FACTORY_CODING_RUNTIME to pi, and FACTORY_PI_MODEL and the other FACTORY_PI_* settings to"
            + " this model.";
    private static final String LIMITS_LINK = "<p class=\"status-card-note\"><a class=\"dev-link\"\\s+"
            + "href=\"/developer/configuration#limits\">Limits in Configuration</a></p>";

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
    private TaskRepository taskTasks;

    @Autowired
    private TaskAttemptRepository taskAttempts;

    @Autowired
    private TaskInstallationRepository taskInstallations;

    @Autowired
    private TaskAdmissionService taskAdmissions;

    @Autowired
    private ITemplateEngine templateEngine;

    @Autowired
    private CodingTranscriptStore transcripts;

    @MockitoBean
    private DeveloperPreflight developerPreflight;

    /** Real unless a test changes it; Mockito resets it after each test. */
    @MockitoSpyBean
    private TaskRunSettings runSettings;

    @Autowired
    private CodingRuntime codingRuntime;

    @Autowired
    private DevRuntimeProperties devRuntime;

    @Autowired
    private ModelCatalog.Properties modelsFile;

    @Autowired
    private ServletContext servletContext;

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
                .contains("class=\"charts-grid\"")
                .containsPattern("<span class=\"chip chip-(success|neutral)\">(Running|Disabled)</span>")
                .doesNotContain("badge-success", "badge-muted")
                // The cards count runs by the states the Executions list filters by, in its words.
                .containsPattern("<span class=\"kpi-label\">Completed</span>\\s*</div>")
                .contains("<span class=\"kpi-label\">Pull request opened</span>",
                        "<span class=\"kpi-label\">Waiting for review</span>", "<span class=\"kpi-label\">Failed</span>",
                        "<span class=\"kpi-label\">Cancelled or rejected</span>", "Runs started per day, by state")
                .doesNotContain("Ran to the end", "Failed / escalated", "Awaiting review", "HITL reviews");
        // Runs, reviews and time ranges in plain words, as on the other pages.
        assertThat(body).contains("<span class=\"kpi-label\">Total runs</span>", "<h2 class=\"card-title\">Reviews</h2>",
                        "<dt>Waiting now</dt>", "<dt>Longest wait now</dt>", "<dt>Decided in this period</dt>",
                        "<dt>Average time to a decision</dt>", ">24 hours</a>", "aria-current=\"page\">14 days</a>")
                .doesNotContain("Total executions", "HITL review latency", "Human-in-the-loop", "Avg decision time",
                        "Decided in window", ">24h<", ">14d<");
        Matcher chart = Pattern.compile("src=\"(/webjars/chart\\.js/[^\"]+/chart\\.umd-[0-9a-f]{32}\\.js)\"")
                .matcher(body);
        assertThat(chart.find()).as("hashed chart.js link").isTrue();
        assertThat(rest.get().uri(chart.group(1)).retrieve().toEntity(String.class).getBody()).contains("Chart");
    }

    @Test
    void layoutNamesTheProductAndOffersTheMenuRail() {
        String body = assertRendered("/", "Dashboard");
        assertThat(body)
                .contains("Dashboard — AI FOLIO</title>", "<strong>AI FOLIO</strong>", "id=\"menu-toggle\"",
                        "aria-label=\"Close menu\"", "aria-expanded=\"true\"",
                        "localStorage.getItem('menu') === 'collapsed'")
                .containsPattern("src=\"/js/menu-[0-9a-f]{32}\\.js\"")
                .contains("class=\"btn btn-secondary\" data-confirm-cancel>Cancel</button>")
                .doesNotContain("AI Factory");
    }

    @Test
    void sessionsAreTrackedByCookieOnlySoRedirectsCarryNoSessionId() {
        assertThat(servletContext.getEffectiveSessionTrackingModes()).containsExactly(SessionTrackingMode.COOKIE);
    }

    @Test
    void pageLinksCarryAContentHashAndHashedFilesAreCachedForAYear() {
        ResponseEntity<String> page = rest.get().uri("/flows/dev-factory").retrieve().toEntity(String.class);
        String body = page.getBody();
        assertThat(page.getHeaders().getCacheControl()).as("HTML pages stay uncached").isNull();
        assertThat(body).doesNotContainPattern("\"/(css|js)/[a-z]+\\.(css|js)\"");
        for (String file : List.of("css/tokens", "css/app", "js/app", "js/theme", "js/confirm", "js/poll", "js/menu")) {
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
    void developerConfigurationTabShowsModelsRoutesLimitsAndTheTaskSource() {
        String body = renderDeveloperConfiguration(null, DeveloperPreflight.Status.NOT_RUN);

        // The Developer Flow header with its tabs instead of an own title and a way back.
        assertThat(body).contains("aria-current=\"page\">Configuration</a>", ">Definition</a>")
                .doesNotContain("<h1>Developer Flow configuration</h1>", "Back to flow");
        // Three groups: models without a warning, models whose endpoint Factory cannot reach (the warning once per
        // endpoint, then each model by its summary), and models that cannot run with their fixes.
        assertThat(body).contains("Ready to run <span class=\"dev-count\">(1)</span>", "codemie-opus-5.5",
                        "Endpoint not reachable <span class=\"dev-count\">(2)</span>", "codemie-sonnet-5.5",
                        "codemie-haiku-4.5",
                        "default · host.docker.internal:4001 · effort low, medium, high, xhigh, max · price from"
                                + " platform.claude.com, 2026-11-03",
                        "Cannot run <span class=\"dev-count\">(2)</span>", "glm-5.3",
                        "FACTORY_ZAI_API_KEY is empty. Set it in .env.", "codemie-gpt-5.6-luna", LUNA_FIX)
                .containsOnlyOnce("Factory cannot open a connection to http://host.docker.internal:4001. Runs with"
                        + " these models fail if the coding container cannot reach it either. Start the service at"
                        + " that address, or fix base-url in config/developer-models.yaml.")
                .doesNotContain("Endpoint not reachable: Factory");
        assertThat(body).contains(">Where runs go</h2>", ">Jira project</th>", ">Pull requests to</th>",
                        ">MODSIDECAR</span>", "folio-org/folio-module-sidecar, branch master",
                        "mvn -B -ntp test in maven:3.9-eclipse-temurin-21", "docker compose restart factory",
                        "FACTORY_SIDECAR_DELIVERY_REPO")
                .doesNotContain("Jira projects configured:");
        assertThat(body).contains("<section class=\"dev-list\" id=\"limits\" aria-labelledby=\"limits-title\">",
                        ">Limits for every run</h2>", ">Time per step</dt>",
                        "30 min each for the first build, the coding and the checks",
                        "FACTORY_DEV_FACTORY_TIMEOUT_SECONDS", ">Time per attempt</dt>", "1 h from Prepare task",
                        "FACTORY_TASKS_EXECUTION_DEADLINE", ">Agent turns</dt>", "FACTORY_CLAUDE_CODE_MAX_TURNS",
                        ">Coding image</dt>", "FACTORY_CLAUDE_CODE_IMAGE")
                // Name, value and the variable that sets it are three cells.
                .containsPattern("<dt>Time per step</dt>\\s*<dd>30 min each for the first build[^<]*</dd>\\s*"
                        + "<dd class=\"config-variable\"><code>FACTORY_DEV_FACTORY_TIMEOUT_SECONDS</code></dd>")
                .containsPattern("<dt>Search</dt>\\s*<dd><code>[^<]+</code>[\\s\\S]*?</dd>\\s*"
                        + "<dd class=\"config-variable\"></dd>");
        assertThat(body).contains(">Tasks come from</h2>", "jira.example.org", "Jane Developer",
                        "assignee = &quot;Jane Developer&quot; AND statusCategory != Done ORDER BY updated DESC",
                        "href=\"https://jira.example.org/issues/?jql=", ">Jira sign-in</dt>");
        // Only the failing Factory checks, each as its fix; no raw check rows.
        assertThat(body).contains(">Factory cannot start runs</h2>", "Docker daemon is unavailable. Start Docker")
                .doesNotContain("Selected runtime", "Effective repositories", "NOT_RUN", ">PASS<", "FAIL ·",
                        "No model call made.");
        assertThat(body).contains(">Check one task</h2>", "It makes no model call and changes nothing.")
                .doesNotContain("class=\"config-verdict\"");
    }

    @Test
    void developerConfigurationSaysWhyATaskCannotRun() {
        String body = renderDeveloperConfiguration("MODSIDECAR-196", DeveloperPreflight.Status.FAIL);

        assertThat(body).contains("MODSIDECAR-196 cannot run yet. Fix the failed checks below, then check again.",
                        "chip-danger\">Failed</span>",
                        "Push permission check &lt;read-only&gt; &amp; base alignment.", "value=\"MODSIDECAR-196\"")
                .doesNotContain("check <read-only>", "No model call made.");
    }

    @Test
    void developerConfigurationListsThePassedChecksOfATask() {
        String body = renderDeveloperConfiguration("MODSIDECAR-196", DeveloperPreflight.Status.PASS);

        assertThat(body).contains("chip-success\">Passed</span>", "base alignment.")
                .doesNotContain("cannot run yet");
    }

    private String renderDeveloperConfiguration(String issueKey, DeveloperPreflight.Status deliveryStatus) {
        var docker = new DeveloperPreflight.Check("docker", DeveloperPreflight.Status.FAIL,
                "Docker daemon is unavailable. Start Docker; when Factory runs in Compose, check that"
                        + " /var/run/docker.sock is mounted into the factory service.");
        var deliveryCheck = new DeveloperPreflight.Check("delivery:sidecar", deliveryStatus,
                "Push permission check <read-only> & base alignment.");
        var inferenceCheck = new DeveloperPreflight.Check("inference", DeveloperPreflight.Status.NOT_RUN,
                "No model call made.");
        var sonnet = new ModelChecks.ModelRow("codemie-sonnet-5.5", true, true, "claude-sonnet-5-5",
                "anthropic-messages", "http://host.docker.internal:4001", "bearer-token",
                "low, medium, high, xhigh, max; default high",
                "default · host.docker.internal:4001 · effort low, medium, high, xhigh, max · price from"
                        + " platform.claude.com, 2026-11-03",
                List.of(new DeveloperPreflight.Check("endpoint", DeveloperPreflight.Status.WARN,
                        "Endpoint not reachable: Factory cannot open a connection to http://host.docker.internal:4001."
                                + " Start the service at that address.")));
        var haiku = new ModelChecks.ModelRow("codemie-haiku-4.5", false, true, "claude-haiku-4-5",
                "anthropic-messages", "http://host.docker.internal:4001", "bearer-token", "low; default low",
                "host.docker.internal:4001 · effort low · no price", List.of(sonnet.checks().getFirst()));
        var opus = new ModelChecks.ModelRow("codemie-opus-5.5", false, true, "claude-opus-5-5",
                "anthropic-messages", "https://proxy.example.org", "bearer-token", "high; default high",
                "proxy.example.org · effort high · price from platform.claude.com, 2026-11-03",
                List.of(new DeveloperPreflight.Check("endpoint", DeveloperPreflight.Status.PASS, "Reachable.")));
        var glm = new ModelChecks.ModelRow("glm-5.3", false, false, "glm-5.3", "anthropic-messages",
                "https://api.z.ai", "bearer-token", "low, high, max; default max", "api.z.ai · effort low, high, max",
                List.of(new DeveloperPreflight.Check("secret", DeveloperPreflight.Status.FAIL,
                        "FACTORY_ZAI_API_KEY is empty. Set it in .env. If docker-compose.yml does not pass it to the"
                                + " factory service yet, add it there. Then run docker compose up -d.")));
        var luna = new ModelChecks.ModelRow("codemie-gpt-5.6-luna", false, false, "gpt-5.6-luna", "openai-responses",
                "http://host.docker.internal:4001", "api-key", "low, medium, high; default medium",
                "host.docker.internal:4001 · effort low, medium, high",
                List.of(new DeveloperPreflight.Check("api", DeveloperPreflight.Status.FAIL, LUNA_FIX)));
        var checks = issueKey == null ? List.of(docker, inferenceCheck) : List.of(deliveryCheck, inferenceCheck);
        var report = new DeveloperPreflight.Report(deliveryStatus == DeveloperPreflight.Status.PASS,
                issueKey == null ? "" : issueKey, Map.of("runtime", "claude-code"), List.of(), checks,
                List.of(sonnet, haiku, opus, glm, luna));
        when(developerPreflight.check(issueKey)).thenReturn(report);

        String path = "/developer/configuration";
        if (issueKey != null) {
            path += "?issueKey=" + issueKey;
        }
        String body = assertRendered(path, "Developer Flow configuration");
        assertThat(body).doesNotContain(ZAI_KEY, PROXY_TOKEN);
        return body;
    }

    @Test
    void dashboardRendersTokenKpiAndBreakdownTable() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "test-automation", "engine",
                Map.of("promptTokens", 1786, "completionTokens", 353));

        String body = assertRendered("/", "LLM tokens");
        assertThat(body)
                .contains("Token spend by step", "<th>Tokens in</th>", "<th>Tokens out</th>")
                // Grouped thousands, and the per-step row reached the table body: the flow by its name, the step
                // in the words of the Definition tab.
                .contains("2,139")
                .contains("<td class=\"nowrap\">Test Factory</td>", "<td>Test automation</td>")
                .doesNotContain(">test-automation<", ">test-factory<", "<th>Prompt</th>", "<th>Completion</th>");
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
        assertRendered("/reviews", "Steps that wait for a person's decision, and the decisions made.");
    }

    @Test
    void aDeveloperRunWaitingForPrCommentsIsNamedByItsTaskAndOpensItsRunPage() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{\"issueKey\":\"MGRAPPS-21\"}"));
        reviews.save(new HitlReview(execution.getId(), "pr-feedback", 8,
                "{\"title\":\"PR feedback\",\"flowId\":\"dev-factory\"}"));
        var gate = executions.save(new PipelineExecution("test-factory", "1", "{\"issueKey\":\"MGRAPPS-22\"}"));
        var qaReview = reviews.save(new HitlReview(gate.getId(), "qa-gate-1", 2,
                "{\"title\":\"QA review: manual test cases\",\"flowId\":\"test-factory\"}"));

        String body = assertRendered("/reviews", "Steps that wait for a person's decision, and the decisions made.");

        assertThat(body).contains("<td>Waiting for PR comments: MGRAPPS-21</td>",
                        "href=\"/executions/" + execution.getId() + "#pr-feedback\">PR feedback</a>",
                        "<td>QA review: manual test cases</td>", "href=\"/reviews/" + qaReview.getId() + "\">Review</a>")
                .doesNotContain("<td>PR feedback</td>", "MGRAPPS-22");
        // Each flow by its name and each gate in words, on one line; the action sits at the right edge of the row.
        assertThat(body).contains("<td class=\"nowrap\">Developer Flow</td>", "<td class=\"nowrap\">PR feedback</td>",
                        "<td class=\"nowrap\">Test Factory</td>", "<td class=\"nowrap\">QA gate 1</td>",
                        "<td class=\"cell-action\">", "<th class=\"cell-action\">")
                .doesNotContain(">dev-factory<", ">pr-feedback<", ">qa-gate-1<", "Human-in-the-loop");
    }

    @Test
    void executionsListRenders() {
        var execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        var stored = executions.findById(execution.getId()).orElseThrow();

        // The list says its runs and its Status filter in words, names each flow, and shows its times without seconds.
        assertThat(assertRendered("/executions?flow=test-factory", "The runs of every flow, newest first."))
                .contains("<a class=\"table-link\" href=\"/executions/" + execution.getId() + "\">", "<th>Run</th>",
                        "<th>Step</th>", "<td>0</td>", "<td class=\"nowrap\">Test Factory</td>", "class=\"chip chip-info\">In progress</span>",
                        "<option value=\"FAILED\">Failed</option>", "<option value=\"PULL_REQUEST\">Pull request opened</option>",
                        "<option value=\"test-factory\" selected=\"selected\">Test Factory</option>",
                        "<td class=\"muted nowrap\">" + timeTag(PageTime.day(stored.getCreatedAt())) + "</td>",
                        "<td class=\"muted nowrap\">" + timeTag(PageTime.day(stored.getUpdatedAt())) + "</td>")
                .doesNotContain("<th>Execution</th>", ">FAILED_ESCALATED<", "Pipeline runs");
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
        String body = assertRendered("/reviews?status=ALL", "Steps that wait for a person's decision, and the decisions made.");
        var stored = reviews.findById(review.getId()).orElseThrow();
        assertThat(body).contains("Signed-off plan").contains("Decided")
                // Each time is short UTC text with its instant, which time.js turns into the browser's local time.
                .contains(timeTag(PageTime.day(stored.getCreatedAt())), timeTag(PageTime.day(stored.getDecidedAt())));
    }

    @Test
    void promptsListShowsReadOnlyView() {
        String body = assertRendered("/prompts", "Bundled prompt templates");
        // The View action sits at the right edge of its row.
        assertThat(body).contains(">View<", "<th class=\"cell-action\">", "<td class=\"cell-action\">")
                .doesNotContain("Overridden");
    }

    @Test
    void statusPageRenders() {
        String body = assertRendered("/status", "Execution engine");
        // The registration time is short, as in every list; its title keeps the seconds.
        assertThat(body).containsPattern("<td class=\"muted\"><time datetime=\"\\d{4}-\\d{2}-\\d{2}T[\\d:]{8}Z\" "
                + "data-local=\"day\" title=\"[\\d-]{10} [\\d:]{8} UTC\">[A-Z][a-z]{2} \\d{1,2}, [\\d:]{5} UTC</time></td>")
                .contains("class=\"chip chip-neutral\">Not configured</span>",
                        "class=\"chip chip-neutral\">Disabled</span>")
                .doesNotContain("badge-success", "badge-outline");

        // Jira without sign-in still serves My tasks; the Developer Flow opens on its Definition here.
        assertThat(body).contains("Not configured for Test Factory sync, which needs FACTORY_CONNECTORS_JIRA_EMAIL",
                        "My tasks reads Jira with FACTORY_CONNECTORS_JIRA_BASE_URL alone",
                        "href=\"/developer/configuration\">Developer Flow configuration</a>",
                        "href=\"/flows/dev-factory?view=definition\">dev-factory</a>",
                        "href=\"/flows/test-factory\">test-factory</a>");
        // The Actuator endpoints are a list of links that says what they are, not tabs over an empty panel.
        assertThat(body).contains("Raw health and metrics endpoints of the app",
                        "<dt>Health</dt><dd><a class=\"dev-link\" href=\"/actuator/health\">/actuator/health</a></dd>")
                .doesNotContain("aria-label=\"Actuator endpoints\"");
    }

    @Test
    void auditPageRenders() {
        assertRendered("/audit", "Append-only record");
    }

    @Test
    void flowsListRenders() {
        assertThat(assertRendered("/flows", "The flows this Factory can run. Open one to see its steps and runs."))
                .contains("<span class=\"badge badge-outline\">Manual start</span>",
                        "<span class=\"badge badge-outline\">Jira: issue transitioned</span>")
                .doesNotContain("Registered flow plugins", ">manual<", ">jira.issue.transitioned<");
    }

    @Test
    void flowDetailRenders() {
        String body = assertRendered("/flows/test-factory", "Step retries: up to 2, waiting 30 s, then 2 min.");
        // The trigger-dialog sample <script> block must hold raw (un-escaped) JSON so the
        // sample-fill helper copies valid text into the textarea. The escaped form only
        // appears in the textarea itself, where the browser decodes it back.
        assertThat(body)
                .contains("type=\"application/json\"")
                .contains("\"issueKey\"");
        // One numbered list of steps: agent steps with what they read and write, review steps with their
        // instructions and the artifacts they show.
        assertThat(body).contains(">1 · Triage</div>", "Worker triage-agent",
                        "the trigger payload → scope_manifest.md", ">3 · QA review: manual test cases</div>",
                        "Human review", "Reviews test_plan.md, scope_manifest.md",
                        "Worker test-automation-agent, framework: karate",
                        "<div class=\"def-step-title\">Manual start</div>", ">Needs issueKey</div>",
                        "<div class=\"def-step-title\">Jira: issue transitioned</div>",
                        "When /issue/fields/status/name is Ready for QA; needs issueKey",
                        "<summary>Descriptor YAML</summary>")
                .containsPattern("<p class=\"def-step-note\">Review the generated manual test cases")
                .doesNotContain("class=\"stepper\"", ">Pipeline<", "Retry policy", ">AGENT<", ">HITL_GATE<",
                        "details-inline", "Agent step", "Any payload", ">manual<");
    }

    @Test
    void workersListRenders() {
        assertThat(assertRendered("/workers", "agent worker library"))
                .contains("href=\"/flows/dev-factory?view=definition\">dev-factory · read-task</a>",
                        "href=\"/flows/test-factory\">test-factory · triage</a>",
                        // An id stays on one line, and every worker names its class.
                        "<td class=\"nowrap\">dev-coding-decision-gate</td>", "<code>FeedbackInitializer</code>",
                        "<td class=\"cell-action\">")
                .doesNotContain("<code></code>");
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
        var plan = artifactStore.getLatest(execution.getId(), "test_plan.md").orElseThrow();
        var event = auditLog.forExecution(execution.getId()).getFirst();
        assertThat(body)
                .contains("v1 · test-spec · " + timeTag(PageTime.timestamp(plan.getCreatedAt())),
                        "<td>" + timeTag(PageTime.timestamp(event.getOccurredAt())) + "</td>")
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
                .contains("<p class=\"status-card-headline\">Implementing changes</p>")
                .containsPattern("<span class=\"status-card-label\">Retry pending</span>\\s*<span>Starting build hit a"
                        + " network/download failure")
                .doesNotContain("Stop reason", "Previous attempt");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        executions.save(execution);
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("<p class=\"status-card-headline\">This run failed</p>")
                .containsPattern("<span class=\"status-card-label\">Reason</span>\\s*<span>Starting build hit a"
                        + " network/download failure")
                .doesNotContain("Retry pending");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.RUNNING);
        executions.save(execution);
        // Running again after the failure: the card says what the step retries after, until coding starts.
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("<p class=\"status-card-headline\">Implementing changes</p>")
                .containsPattern("<span class=\"status-card-label\">Retrying after</span>\\s*<span>Starting build hit a"
                        + " network/download failure")
                .doesNotContain("Retry pending");
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_starting", "provider", "test-provider", "model", "test-model"));
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .doesNotContain("Starting build hit a network/download failure", "Retry pending", "Retrying after");
    }

    @Test
    void aRunThatStoppedOnAQuestionShowsTheQuestionAndVerifyAndPullRequestAsNotStarted() {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setCurrentStepIndex(10);
        execution = executions.save(execution);
        artifactStore.put(execution.getId(), "dev_verification.json", "{\"state\":\"NOT_RUN\"}", "application/json", "test");
        artifactStore.put(execution.getId(), "dev_delivery.json", "{\"state\":\"NOT_RUN\"}", "application/json", "test");
        artifactStore.put(execution.getId(), "dev_coding_outcome.json",
                "{\"status\":\"NEEDS_DECISION\",\"decision\":{\"question\":\"Keep the old endpoint?\"}}",
                "application/json", "test");

        String body = assertRendered("/executions/" + execution.getId(), "Verify changes");
        assertThat(body)
                .contains("class=\"chip chip-warning\">Asked a question</span>",
                        "<p class=\"status-card-headline\">The agent stopped with a question</p>",
                        "Keep the old endpoint?")
                .containsPattern("data-state=\"question\">\\s*<span class=\"run-step-title\">2 · Implement changes</span>"
                        + "\\s*<span class=\"run-step-state\"><span>Stopped with a question</span>")
                .containsPattern("data-state=\"pending\">\\s*<span class=\"run-step-title\">3 · Verify changes</span>"
                        + "\\s*<span class=\"run-step-state\"><span>Not started</span>")
                .containsPattern("data-state=\"pending\">\\s*<span class=\"run-step-title\">4 · Create pull request"
                        + "</span>\\s*<span class=\"run-step-state\"><span>Not started</span>")
                .doesNotContain("run-stages", "NEEDS_DECISION", "NOT_RUN");
    }

    @Test
    void prFeedbackShowsNewCommentsRoundsWithFactorysCommentAndOneFilledButton() {
        var execution = new PipelineExecution("dev-factory",
                org.folio.factory.devfactory.feedback.FeedbackArtifacts.VERSION, "{}");
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        execution = executions.save(execution);
        var id = execution.getId();
        var pr = "{\"repository\":\"o/mgr-applications\",\"number\":6,\"branch\":\"factory/x\",\"baseBranch\":\"master\","
                + "\"headSha\":\"" + "c".repeat(40) + "\",\"url\":\"https://github.com/o/mgr-applications/pull/6\",\"open\":true}";
        var done = "{\"id\":\"inline:1\",\"author\":\"reviewer\",\"body\":\"Add minProperties\",\"path\":\"a.json\","
                + "\"line\":3,\"outdated\":false,\"updatedAt\":\"\",\"url\":\"https://github.com/o/mgr-applications/pull/6#discussion_r1\"}";
        var fresh = "{\"id\":\"inline:2\",\"author\":\"yauhen\",\"body\":\"Rename the test\",\"path\":\"AppTest.java\","
                + "\"line\":214,\"outdated\":false,\"updatedAt\":\"\",\"url\":\"https://github.com/o/mgr-applications/pull/6#discussion_r2\"}";
        var round = "\"round\":1,\"pr\":" + pr + ",\"selected\":[" + done + "],\"roundStartedAt\":\"2026-11-10T18:22:00Z\"";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"CODE\"}", "application/json", "test");
        var checks = "{\"executionId\":\"" + id + "\",\"argv\":[],\"exitCode\":0,\"surefireReportCount\":1,"
                + "\"testCount\":300,\"failureCount\":0,\"errorCount\":0,\"result\":\"PASS\","
                + "\"output\":\"BUILD SUCCESS from the checks\",\"failsafeReportCount\":0}";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"READY\",\"commitSha\":\"" + "c".repeat(40)
                + "\",\"summary\":\"## Changes\\n- Added `minProperties` to the items object (PR feedback inline:1)."
                + "\\n\\n## Assumptions\\n- None.\",\"finishedAt\":\"2026-11-10T18:28:00Z\",\"runtimeName\":\"Pi\","
                + "\"verification\":" + checks + "}", "application/json", "test");
        // Round 2 stored Factory's own summary, which has no Changes section and so is no report of the agent.
        var second = "\"round\":2,\"pr\":" + pr + ",\"selected\":[" + done + "],\"roundStartedAt\":\"2026-11-10T18:40:00Z\"";
        artifactStore.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"CODE\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"READY\",\"commitSha\":\"" + "d".repeat(40)
                + "\",\"summary\":\"Verified changes published to the same PR\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_comments.json", "{\"id\":\"s1\",\"pr\":" + pr + ",\"comments\":[" + done + "," + fresh + "]}",
                "application/json", "test");
        artifactStore.put(id, "dev_delivery.json", "{\"state\":\"DELIVERED\",\"pullRequestUrl\":"
                + "\"https://github.com/o/mgr-applications/pull/6\",\"pullRequestDraft\":true}", "application/json", "test");
        reviews.save(new HitlReview(id, "pr-feedback", execution.getCurrentStepIndex(), "{}"));
        auditLog.record(id, AuditEventType.CONNECTOR_ACTION, "pr-follow-up", Map.of("action", "pr-round-comment",
                "round", 1, "commentUrl", "https://github.com/o/mgr-applications/pull/6#issuecomment-9"));

        String body = assertRendered("/executions/" + id, "New PR comments");
        // While Factory waits for comments, the card names the pull request and nothing ticks.
        assertThat(body.substring(body.indexOf("class=\"status-card\""), body.indexOf("class=\"run-steps\"")))
                .contains("<a class=\"chip chip-neutral ext-link\" href=\"https://github.com/o/mgr-applications/pull/6\">"
                        + "Draft PR #6</a>", "<p class=\"status-card-headline\">Pull request #6 is open as a draft</p>")
                .doesNotContain("Waiting for your review", "so far", "data-elapsed-start");
        assertThat(body).contains(">Check PR comments</button>",
                        "id=\"comment-0\"", "aria-labelledby=\"comment-0-text\"", "for=\"comment-0\"",
                        "id=\"comment-0-text\">Rename the test</span>",
                        "href=\"https://github.com/o/mgr-applications/pull/6#discussion_r2\">AppTest.java:214</a>",
                        "data-selection=\"selectedIds\"", ">Fix 1 comment</button>", "Starts a new round and adds to the cost.",
                        ">Round 1</strong>", "<span>1 comment</span>",
                        "href=\"https://github.com/o/mgr-applications/pull/6/commits/" + "c".repeat(40) + "\">ccccccc</a>",
                        // The start time in UTC for time.js, then what followed; the title has the whole line.
                        "title=\"Nov 10, 18:22 UTC · 6 min · 300 tests passed\">" + timeTag(PageTime.day(Instant.parse("2026-11-10T18:22:00Z")))
                                + "<span>6 min · 300 tests passed</span></div>",
                        "<p class=\"feedback-round-summary\">Added minProperties to the items object.</p>",
                        "<strong>Comments sent to the agent</strong>", "<p>Add minProperties</p>",
                        "<strong>Agent's report</strong>",
                        "<p class=\"feedback-report\">Changes\n• Added minProperties to the items object.\n\n"
                                + "Assumptions\n• None.</p>", "<p>Checks passed: 300 tests.</p>",
                        "href=\"https://github.com/o/mgr-applications/pull/6#issuecomment-9\">Factory's comment on the PR</a>",
                        "<summary>Round details</summary>", ">Verify my manual changes</button>",
                        "Runs the checks on commits you pushed to the PR branch yourself. No agent, no cost.",
                        "<summary>Activity and usage</summary>", "id=\"usage-title\">First implementation</h2>")
                // One head line: the round and its commit, then the amount and the chip; the rest under it.
                .containsPattern("<div class=\"feedback-round\">\\s*<div class=\"feedback-round-head\"><strong>Round 1"
                        + "</strong>[\\s\\S]*?ccccccc</a></div>\\s*<span class=\"row-amount\">\\$0\\.00</span>"
                        + "\\s*<span class=\"chip chip-success\">Published</span>\\s*<div class=\"feedback-round-body\">")
                .doesNotContain(">Add minProperties</span>", "Work on feedback", "Agent: ", "## ", "inline:1",
                        "BUILD SUCCESS from the checks", "<dt>Models</dt>", "<dt>Runtime</dt>", "<dt>Attempts</dt>",
                        "attempt-cost");
        // One filled button on the page: Fix N comments.
        assertThat(body.split("btn btn-primary", -1)).hasSize(2);
        assertThat(body).contains(">Round 2</strong>").containsOnlyOnce("<strong>Agent's report</strong>")
                .doesNotContain("Verified changes published to the same PR");

        // The transcript names its sections as the page names the work: the implementation, then each round.
        transcriptRun(id, "implement", Instant.parse("2026-11-10T18:00:00Z"));
        transcriptRun(id, "pr-follow-up", Instant.parse("2026-11-10T18:23:00Z"));
        transcriptRun(id, "pr-follow-up", Instant.parse("2026-11-10T18:41:00Z"));
        assertThat(assertRendered("/executions/" + id + "/transcript", "Transcript"))
                .contains("<h2 class=\"dev-section-title\">Implementation</h2>",
                        "<h2 class=\"dev-section-title\">Round 1 · PR feedback</h2>",
                        "<h2 class=\"dev-section-title\">Round 2 · PR feedback</h2>")
                .doesNotContain("Run 2", "Run 3");

        artifactStore.put(id, "dev_pr_comments.json", "{\"id\":\"s2\",\"pr\":" + pr + ",\"comments\":[" + done + "]}",
                "application/json", "test");
        assertThat(assertRendered("/executions/" + id, "New PR comments")).contains("<p class=\"dev-empty\">"
                + "No new comments. Add review comments on GitHub, then press Check PR comments.</p>");
    }

    private void transcriptRun(UUID executionId, String step, Instant at) {
        try (var sink = transcripts.open(executionId, UUID.randomUUID(), step)) {
            sink.add(new CodingTranscripts.Entry("prompt", at, Map.of("text", "Work on " + step)));
        }
    }

    @Test
    void aRoundWithFailedChecksShowsHowManyFailedAndTheirOutput() {
        var execution = new PipelineExecution("dev-factory",
                org.folio.factory.devfactory.feedback.FeedbackArtifacts.VERSION, "{}");
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        execution = executions.save(execution);
        var id = execution.getId();
        var pr = "{\"repository\":\"o/mgr-applications\",\"number\":6,\"branch\":\"factory/x\",\"baseBranch\":\"master\","
                + "\"headSha\":\"" + "c".repeat(40) + "\",\"url\":\"https://github.com/o/mgr-applications/pull/6\",\"open\":true}";
        var round = "\"round\":1,\"pr\":" + pr + ",\"selected\":[],\"roundStartedAt\":\"2026-11-10T18:22:00Z\"";
        var checks = "{\"executionId\":\"" + id + "\",\"argv\":[],\"exitCode\":1,\"surefireReportCount\":1,"
                + "\"testCount\":300,\"failureCount\":2,\"errorCount\":0,\"result\":\"FAIL\","
                + "\"output\":\"AppTest.renames failed\",\"failsafeReportCount\":0}";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"CODE\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"FAILURE\",\"verification\":" + checks
                + "}", "application/json", "test");

        String body = assertRendered("/executions/" + id, "Rounds");
        assertThat(body).contains(">Round 1</strong>", "title=\"Nov 10, 18:22 UTC · 300 tests, 2 failed\">"
                                + timeTag(PageTime.day(Instant.parse("2026-11-10T18:22:00Z"))) + "<span>300 tests, 2 failed</span>",
                        "<p>Checks failed: 2 of 300 tests failed.</p>", "class=\"chip chip-danger\">Action needed</span>")
                .containsPattern("<pre class=\"feedback-diagnostics\"\\s*>AppTest.renames failed</pre>")
                .doesNotContain("Checks passed");
    }

    @Test
    void developerExecutionShowsThePullRequestAsALinkedChipAndHeadline() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        String url = "https://github.com/user/repo/pull/42";
        artifactStore.putMarkdown(execution.getId(), "dev_delivery.json",
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"" + url
                        + "\",\"pullRequestTitle\":\"TASK-1: Fix <routing> & permissions\"}", "publish");

        String body = assertRendered("/executions/" + execution.getId(), "Pull request #42");
        assertThat(body).contains("<a class=\"chip chip-success ext-link\" href=\"" + url + "\">PR #42</a>",
                        "<p class=\"status-card-headline\">Pull request #42 is ready for review</p>")
                .doesNotContain("Open pull request", "<routing>", "DELIVERED");
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
                Map.of("activity", "tool_execution_start", "tool", "Read", "path", "/workspace/src/Foo.java"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_end", "tool", "Read", "path", "/workspace/src/Foo.java",
                        "error", false, "durationMs", 6));
        // A heredoc body and the error output of a command stay in the transcript.
        var heredoc = "cat > Notes.java <<'EOF'\nHIDDEN_HEREDOC_SENTINEL\nEOF";
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_start", "tool", "Bash", "command", heredoc));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_end", "tool", "Bash", "command", heredoc, "error", true,
                        "errorText", "Exit code 2\nHIDDEN_STDERR_SENTINEL"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "turn_start", "message", "HIDDEN_MESSAGE_SENTINEL",
                        "reasoning", "HIDDEN_REASONING_SENTINEL"));
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", Map.of("costUsd", 0));
        String body = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        String activity = body.substring(body.indexOf("<section class=\"dev-list run-activity\""),
                body.indexOf("<section class=\"dev-list run-usage\""));
        assertThat(activity)
                .contains("Activity <span class=\"dev-count\">3</span>",
                        "href=\"/executions/" + execution.getId() + "/transcript\">Full transcript</a>")
                .containsPattern("data-tone=\"live\">\\s*<time datetime=\"\\d{4}-\\d{2}-\\d{2}T[\\d:]{8}Z\" data-local=\"clock\""
                        + " title=\"\\d{4}-\\d{2}-\\d{2} [\\d:]{8} UTC\">[\\d:]{8} UTC</time>\\s*"
                        + "<span class=\"activity-verb\">Running</span>\\s*"
                        + "<code class=\"activity-target\" title=\"mvn test\">mvn test</code>\\s*"
                        + "<span class=\"activity-result\">running now</span>")
                .containsPattern("<span class=\"activity-verb\">Read</span>\\s*<code class=\"activity-target\""
                        + " title=\"/workspace/src/Foo.java\">/workspace/src/Foo.java</code>\\s*"
                        + "<span class=\"activity-result\">took 6 ms</span>")
                .containsPattern("data-tone=\"failed\">[\\s\\S]*?<span class=\"activity-verb\">Ran</span>\\s*"
                        + "<code class=\"activity-target\" title=\"cat &gt; Notes.java &lt;&lt;&#39;EOF&#39;\">"
                        + "cat &gt; Notes.java &lt;&lt;&#39;EOF&#39;</code>\\s*<span class=\"activity-result\">failed · exit 2</span>")
                .doesNotContain("Runtime log, oldest first", "Audit events", "Reading", "data-earlier");
        // The usage column and the technical details: closed, the checks that ran, the starting build output.
        assertThat(body).containsPattern("<details class=\"disclosure\">\\s*<summary>Technical details</summary>")
                .doesNotContain("<details class=\"disclosure\" open")
                .contains("MODSIDECAR-196", "<code>mvn test</code> · exit code 0", "1 · Prepare task",
                        "2 · Implement changes", "3 · Verify changes", "4 · Create pull request",
                        "<summary>Starting build output</summary>", "Compiling 42 source files", "transfer failed",
                        "<summary>Artifacts (4)</summary>", "<dl class=\"tech-facts artifact-list\">", ">Audit log</a>", "No usage reported yet.");
        assertThat(body).doesNotContain("SOURCE_CONTENT_SENTINEL", "HIDDEN_MESSAGE_SENTINEL",
                "HIDDEN_REASONING_SENTINEL", "HIDDEN_HEREDOC_SENTINEL", "HIDDEN_STDERR_SENTINEL", "run-stages",
                "Engine steps", "Execution history", "Now · updates live", "class=\"task-key\"", "class=\"stepper\"",
                "DELIVERY_BLOCKED", "BASELINE_FAILED", "CANDIDATE_UNVERIFIED", "Prepare task checkout", "Pinned base SHA");
        assertThat(visibleText(body.substring(body.indexOf("<div class=\"dev-page\""))))
                .doesNotContainIgnoringCase("execution").doesNotContainIgnoringCase("engine");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.COMPLETED);
        executions.save(execution);
        assertThat(assertRendered("/executions/" + execution.getId(), "Developer Flow"))
                .contains("class=\"chip chip-danger\">Failed</span>", "Safe destination missing")
                .doesNotContain("running now", "DELIVERY_BLOCKED");
    }

    @Test
    void transcriptPageShowsEachRunWithACollapsedPromptAndToolCallsWithTheirResults() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        Instant at = Instant.parse("2026-10-27T09:15:30Z");
        try (var sink = transcripts.open(execution.getId(), UUID.randomUUID(), "implement")) {
            sink.add(new CodingTranscripts.Entry("prompt", at, Map.of("text", "Implement MODBAR-1 <now>")));
            sink.add(new CodingTranscripts.Entry("text", at.plusSeconds(1),
                    Map.of("text", "I will run the tests\n\n## Regexes\nThe pattern is fine.",
                            "thinking", "HIDDEN_REASONING_SENTINEL")));
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
                .contains("<title>Transcript — AI FOLIO</title>", "/ Transcript</nav>",
                        ">run " + execution.getId().toString().substring(0, 4) + "…</a>",
                        "<details class=\"disclosure\">", "<h2 class=\"dev-section-title\">Implementation</h2>",
                        // A Markdown heading of the agent is a bold line, not a heading of the page.
                        "<p class=\"transcript-text\"><strong>Regexes</strong></p>",
                        "<p class=\"transcript-text\">The pattern is fine.</p>",
                        "Started " + timeTag(PageTime.timestamp(Instant.parse("2026-10-27T09:15:30Z"))),
                        "<summary>Prompt sent to the agent</summary>", "Implement MODBAR-1 &lt;now&gt;",
                        "I will run the tests", "<strong>Bash</strong>", "failed", "3 s",
                        "{ &quot;command&quot; : &quot;mvn test&quot; }", "BUILD FAILURE",
                        "[cut: 120 more characters]", "<strong>Final result</strong>",
                        "4 turns · 6 s · 1,200 input tokens · 80 output tokens",
                        "Transcript cut at 5 MB; the attempt went on.",
                        timeTag(PageTime.clock(Instant.parse("2026-10-27T09:15:32Z"))),
                        "href=\"/executions/" + execution.getId() + "\"")
                .doesNotContain("<details open", "HIDDEN_REASONING_SENTINEL", "<now>", "run-panel", "## Regexes",
                        "Run 1");

        var empty = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        assertThat(assertRendered("/executions/" + empty.getId() + "/transcript", "Transcript"))
                .contains("No transcript was stored for this run.");
    }

    @Test
    void codingFailureReasonsReachTheTaskScreensAndTheRunPage() {
        resetTaskData();
        String turnLimit = "Claude Code used all 40 turns before it finished the task."
                + " Retry with more turns or a different effort.";
        Task stopped = seedTask("70081", "MODBAR-781", "Turn limit task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = new PipelineExecution("dev-factory", "0.8.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_result.json",
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"" + turnLimit + "\"}", "verify");
        TaskAttempt attempt = seedAttempt(stopped, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "DEVELOPMENT_FAILED: " + turnLimit);
        attempt.setExecutionId(execution.getId());
        attempt.setStartedAt(Instant.parse("2026-11-10T22:42:00Z"));
        taskAttempts.save(attempt);
        Task provider = seedTask("70082", "MODBAR-782", "Provider task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(provider, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "shared: DEVELOPMENT_FAILED: Claude Code stopped with an error (subtype success, API status 429)."
                        + " Check the provider and the Developer Flow settings, then retry. Error: API Error: 429");

        // A reason stored by an older run reads like the reason a run stores now: no layer prefix, what to do.
        String expected = "The agent reached its limit of 40 turns before it finished."
                + " Start a new attempt with narrower instructions.";
        String providerError = "Claude Code stopped with an error (subtype success, API status 429).";
        Task unchanged = seedTask("70083", "MODBAR-783", "Unchanged task", Task.Eligibility.ELIGIBLE,
                "repository mod-bar");
        seedAttempt(unchanged, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "DEVELOPMENT_FAILED: Candidate contains no changes");
        String list = assertRendered("/flows/dev-factory", "Ready to run");
        // The Runs row shows the first sentence on one line, with the whole reason in the title.
        assertThat(list)
                .containsPattern("<div class=\"dev-row-meta\" title=\"MODBAR-\\d+ · [^\"]+ · " + Pattern.quote(expected)
                        + "\"\\s*>[\\s\\S]*?<span>The agent reached its limit of 40 turns before it finished.</span></div>")
                .containsPattern("title=\"MODBAR-782 · [^\"]+ · " + Pattern.quote(providerError + " Check the provider"
                        + " and the Developer Flow settings, then retry. Error: API Error: 429.") + "\"\\s*>[\\s\\S]*?<span>"
                        + Pattern.quote(providerError) + "</span></div>")
                // The age stays relative; time.js puts the local time into its title.
                .containsPattern("</a><time datetime=\"[\\dT:-]{19}Z\" data-local=\"ago\" title=\"[\\d-]{10} [\\d:]{8} UTC\">")
                .doesNotContain("Implementation failed", "shared: ", "DEVELOPMENT_FAILED");
        String task = assertRendered("/tasks/" + stopped.getId(), "Failed");
        assertThat(task)
                // Chip and headline are one group, so the amount wraps under the text and the chip stays on its line.
                .containsPattern("<div class=\"status-card-main\">\\s*<span class=\"chip chip-danger\">Failed</span>")
                .contains("<p class=\"status-card-headline\">Attempt 1 failed</p>")
                .containsPattern("<span class=\"status-card-label\">Reason</span>\\s*<span>" + Pattern.quote(expected)
                        + "</span>")
                .containsPattern("<p class=\"status-card-note\">\\s*No pull request was created.</p>")
                // A limit says where it is set, as a link under the reason, not inside the sentence.
                .containsPattern(LIMITS_LINK)
                .doesNotContain("Implementation failed", "DEVELOPMENT_FAILED");
        // The attempt chip keeps the reason and what to do in its title; the meta line has its start time for
        // time.js, and no title in UTC, since the line itself shows the local time.
        assertThat(task.substring(task.indexOf("id=\"attempts-title\"")))
                .contains("title=\"" + expected + "\">Failed</span>",
                        "<div class=\"dev-row-meta dev-row-meta-dots dev-row-meta-quoted\">"
                                + timeTag(PageTime.day(Instant.parse("2026-11-10T22:42:00Z"))) + "</div>");
        // The run page says it in the same words, and links back to its task and attempt.
        String run = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(run)
                .contains("<title>MODBAR-781 · attempt 1 — AI FOLIO</title>",
                        "href=\"/tasks/" + stopped.getId() + "\">MODBAR-781</a>", "<span>Attempt 1</span>",
                        "<p class=\"status-card-headline\">Attempt 1 failed</p>")
                .containsPattern("<div class=\"status-card-main\">\\s*<span class=\"chip chip-danger\">Failed</span>")
                .containsPattern("<span class=\"status-card-label\">Reason</span>\\s*<span>" + Pattern.quote(expected)
                        + "</span>")
                .containsPattern("<p class=\"status-card-note\">\\s*No pull request was created.</p>")
                .containsPattern(LIMITS_LINK)
                .doesNotContain("Stop reason", "DEVELOPMENT_FAILED", "Implementation failed");
        // A failure that is not a limit has no such link.
        assertThat(assertRendered("/tasks/" + provider.getId(), "Failed")).doesNotContain("configuration#limits");
        assertThat(assertRendered("/tasks/" + unchanged.getId(), "Failed"))
                .containsPattern("<span class=\"status-card-label\">Reason</span>\\s*<span>The agent finished without"
                        + " changing any file. Start a new attempt with clearer instructions.</span>")
                .doesNotContain("Candidate contains no changes");
    }

    @Test
    void developerExecutionShowsEstimatedCostEvidenceWithoutRuntimeCost() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "runtime", "pi", "provider", "test-provider",
                        "model", "glm-5.3-flash", "image", "factory-dev-pi:0.85.1-java21"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "pi_usage", "inputTokens", 100_000, "cacheReadTokens", 10_000,
                        "cacheWriteTokens", 0, "outputTokens", 10_000, "costUsd", 0));

        String body = assertRendered("/executions/" + execution.getId(), "<dt>Pricing</dt>");
        assertThat(usageColumn(body)).contains("<h2 class=\"dev-section-title\" id=\"usage-title\">Usage</h2>",
                        "<dt>Input</dt>", "<dt>Cache read</dt>", "<dt>Cache write</dt>", "<dt>Output</dt>",
                        "<dt>Total tokens</dt>", ">Estimated cost</dt>", "$0.02")
                .doesNotContain("<dt>Model</dt>", "<dt>Provider</dt>", "<dt>Runtime</dt>", "<dt>Image</dt>");
        // Model and effort are in the header; provider, runtime and image are not on the page.
        assertThat(body).contains("<span>glm-5.3-flash</span>", "2026-09-19", "docs.z.ai",
                        "Real provider charges may differ.")
                // A price source that is a web address opens in a new tab.
                .containsPattern("<a class=\"dev-link ext-link\" href=\"(https://[^\"]+)\" target=\"_blank\""
                        + " rel=\"noopener noreferrer\">\\1</a>")
                .containsPattern("<span class=\"status-card-money\">\\$0.02</span>\\s*"
                        + "<span class=\"status-card-note\">so far</span>")
                .doesNotContain("test-provider", "factory-dev-pi", "Runtime and usage", "Coding runtime-reported cost");
    }

    @Test
    void developerExecutionShowsTheConfiguredModelAcrossStepsAndTheRunningBuild() {
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
            String activity = body.substring(body.indexOf("<section class=\"dev-list run-activity\""),
                    body.indexOf("<section class=\"dev-list run-usage\""));
            assertThat(activity).containsPattern("<span class=\"activity-verb\">Building</span>\\s*"
                            + "<code class=\"activity-target\" title=\"the project\">the project</code>\\s*"
                            + "<span class=\"activity-result\">running now</span>")
                    .doesNotContain("Starting build started");
            assertThat(body).containsOnlyOnce("id=\"activity-title\"")
                    .contains("<span>planned-model</span>", "No usage reported yet.")
                    .doesNotContain("coding-image", "test-provider", "coding configured");
        }
    }

    @Test
    void developerExecutionShowsTheModelAndEffortInTheHeader() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{}"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "runtime", "claude-code", "provider", "anthropic-messages",
                        "modelId", "codemie-sonnet-5.5", "model", "claude-sonnet-5-5", "effort", "high",
                        "maxTurns", 200));
        // A run from before the models file named its run profile.
        var legacy = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{}"));
        auditLog.record(legacy.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "coding_starting", "runtime", "claude-code", "provider", "anthropic-messages",
                        "profile", "glm-5.3", "profileLabel", "GLM-5.3 (Z.ai)", "model", "glm-5.3",
                        "effort", "max", "maxTurns", 100));

        assertThat(header(assertRendered("/executions/" + execution.getId(), "Developer Flow")))
                .contains("<span>codemie-sonnet-5.5 · high</span>")
                .doesNotContain("claude-sonnet-5-5", "Max turns", "200");
        assertThat(header(assertRendered("/executions/" + legacy.getId(), "Developer Flow")))
                .contains("<span>GLM-5.3 (Z.ai) · max</span>");
    }

    private static String usageColumn(String body) {
        return body.substring(body.indexOf("<section class=\"dev-list run-usage\""),
                body.indexOf("<div class=\"run-disclosures\">"));
    }

    private static String header(String body) {
        return body.substring(body.indexOf("<div class=\"dev-heading\">"), body.indexOf("class=\"status-card\""));
    }

    /** The text a person reads: tags and their attributes left out. */
    private static String visibleText(String html) {
        return html.replaceAll("<script[\\s\\S]*?</script>", " ").replaceAll("<[^>]+>", " ");
    }

    @Test
    void developerExecutionShowsOnlyTheModelOfAPartialRuntimeIdentity() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        for (String field : List.of("model", "provider", "runtime", "image")) {
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "coding_starting", field, "reported-" + field));
            String body = assertRendered("/executions/" + execution.getId(), "Usage");
            assertThat(usageColumn(body)).contains("No usage reported yet.")
                    .doesNotContain("Total tokens", "Estimated cost");
            if (field.equals("model"))
                assertThat(header(body)).contains("<span>reported-model</span>");
            else
                assertThat(body).doesNotContain("reported-" + field);
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

        String body = assertRendered("/executions/" + execution.getId(), "Usage");
        assertThat(usageColumn(body)).contains("Some usage was not reported, so the real cost may be higher.")
                .doesNotContain("No price is set", ">Estimated cost</dt>");
        assertThat(body).doesNotContain("<dt>Pricing</dt>");
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
                Map.of("activity", "coding_starting", "provider", "test-provider", "model", "glm-5.3-flash"));
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
                        "outputTokens", 10_000, "provider", "test-provider", "model", "glm-5.3-flash"));

        String body = assertRendered("/executions/" + execution.getId(), "Activity");
        assertThat(body).contains("<p class=\"status-card-headline\">This run failed</p>", "HTTP 429 (rate limited)",
                        "310k", "$0.03", "Coding runtime retrying request (1/3) · HTTP 429 (rate limited)",
                        "Provider request failed · HTTP 429 (rate limited)")
                .doesNotContain("Provider diagnostics", "Coding runtime-reported cost", "PROVIDER_RETRIES_EXHAUSTED");
    }

    @Test
    void developerAuditVolumeOfRowlessEventsLeavesActivityEmpty() {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(3);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution = executions.save(execution);
        for (int i = 0; i < 240; i++)
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "turn_start"));

        String body = assertRendered("/executions/" + execution.getId(), "Technical details");

        assertThat(body)
                .containsOnlyOnce("id=\"activity-title\"")
                .contains("Activity <span class=\"dev-count\">0</span>", "No activity reported yet.",
                        "href=\"/audit?executionId=" + execution.getId() + "\">Audit log</a>")
                .doesNotContain("Execution history", "data-developer-history", "Raw audit history");
    }

    @Test
    void activityShowsTheNewestTwentyRowsAndKeepsTheRestBehindShowEarlier() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{}"));
        for (int i = 1; i <= 25; i++)
            auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                    Map.of("activity", "tool_execution_start", "tool", "Read", "path", "Foo" + i + ".java"));

        String body = assertRendered("/executions/" + execution.getId(), "Activity");

        assertThat(body).contains("Activity <span class=\"dev-count\">25</span>",
                        "data-show-earlier>Show 5 earlier</button>")
                .containsPattern("data-earlier=\"true\" hidden=\"hidden\">[\\s\\S]*?title=\"Foo5.java\"");
        assertThat(body.split("data-earlier=\"true\"", -1)).hasSize(6);
        assertThat(body.indexOf("title=\"Foo25.java\"")).isLessThan(body.indexOf("title=\"Foo1.java\""));
    }

    @Test
    void completedDeveloperExecutionsRenderProductOutcomeInWordsAndInTheList() {
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
            var chip = outcome.equals("DELIVERED") ? "class=\"chip chip-success\">Finished</span>"
                    : "class=\"chip chip-danger\">Failed</span>";
            assertThat(detail).contains(chip)
                    .doesNotContain("chip-status-", ">" + outcome + "<", ">COMPLETED<", "Engine status", "now-title");
            if (outcome.equals("DEVELOPMENT_FAILED"))
                assertThat(usageColumn(detail)).containsSubsequence("<dt>Input</dt>", "<dt>Cache read</dt>",
                                "<dt>Cache write</dt>", "<dt>Output</dt>", "<dt>Total tokens</dt><dd>16</dd>")
                        .doesNotContain("pi usage", "Coding runtime-reported cost");
            // The list has the same words: a run without a pull request failed.
            String list = assertRendered("/executions?flow=dev-factory", "Executions");
            assertThat(list).contains(chip).doesNotContain(">" + outcome + "</span>", "chip-status-COMPLETED",
                    ">Completed</span>");
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
                .contains("class=\"chip chip-status-COMPLETED\">COMPLETED</span>")
                .doesNotContain("chip-status-DEVELOPMENT_FAILED", "Engine status", "badge-status-");
        assertThat(assertRendered("/executions?flow=test-factory", "Executions"))
                .contains("class=\"chip chip-success\">Completed</span>")
                .doesNotContain("chip-status-DEVELOPMENT_FAILED", ">Failed</span>");
    }

    @Test
    void developerRunsInTheListShowTheirTaskKeyAttemptAndStatusInWordsWithoutAStep() {
        resetTaskData();
        var question = new PipelineExecution("dev-factory", "0.9.0", "{\"issueKey\":\"MGRAPPS-64\"}");
        question.setStatus(ExecutionStatus.AWAITING_HITL);
        question.setCurrentStepIndex(4);
        question = executions.save(question);
        var secondAttempt = seedAttempt(seedTask("70064", "MGRAPPS-64", "Asked task", Task.Eligibility.ELIGIBLE,
                "repository mgr-applications"), 2, TaskAttempt.Kind.RETRY, TaskAttempt.State.RUNNING, null);
        secondAttempt.setExecutionId(question.getId());
        taskAttempts.save(secondAttempt);
        artifactStore.putMarkdown(question.getId(), "dev_coding_outcome.json", "{\"status\":\"NEEDS_DECISION\"}",
                "implement");
        var draft = new PipelineExecution("dev-factory", "0.9.0", "{\"issueKey\":\"MGRAPPS-21\"}");
        draft.setStatus(ExecutionStatus.COMPLETED);
        draft.setCurrentStepIndex(7);
        draft = executions.save(draft);
        artifactStore.putMarkdown(draft.getId(), "dev_delivery.json", "{\"state\":\"DELIVERED\","
                + "\"pullRequestUrl\":\"https://github.com/folio-org/mgr-applications/pull/412\","
                + "\"pullRequestDraft\":true}", "publish");

        String list = assertRendered("/executions?flow=dev-factory", "Executions");

        // An attempt in flight is In progress, as its task page says, whatever its run waits for.
        assertThat(list).contains("href=\"/executions/" + question.getId() + "\">MGRAPPS-64 · attempt 2</a>",
                        "class=\"chip chip-info\">In progress</span>",
                        "<a class=\"table-link\" href=\"/executions/" + draft.getId() + "\">MGRAPPS-21</a>",
                        "class=\"chip chip-neutral\">Draft PR #412</span>")
                .doesNotContain(">NEEDS_DECISION<", ">DELIVERED<", "<th>Step</th>", "<td>4</td>", "<td>7</td>");
    }

    @Test
    void artifactsListAndDetailRender() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        var artifact = artifactStore.putMarkdown(execution.getId(), "coverage_report.md",
                "# Coverage\nAll branches hit.\n", "test-exec");

        var created = timeTag(PageTime.timestamp(artifact.getCreatedAt()));
        String list = assertRendered("/artifacts", "Immutable artifact versions");
        // The list has the short time; the artifact page keeps the seconds.
        assertThat(list).contains("coverage_report.md").contains("test-factory")
                .contains("<td class=\"muted\">" + timeTag(PageTime.day(artifact.getCreatedAt())) + "</td>");

        String detail = assertRendered("/artifacts/" + artifact.getId(), "All branches hit.");
        assertThat(detail).contains("coverage_report.md").contains("test-exec · " + created + " · text/markdown");
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

    // ----- task screens -----

    private void resetTaskData() {
        new TaskFixture(taskTasks, taskAttempts, taskInstallations, taskAdmissions).reset();
    }

    private Task seedTask(String issueId, String key, String title,
                               Task.Eligibility eligibility, String routingReason) {
        return seedTask(issueId, key, title, "Open", eligibility, routingReason);
    }

    private Task seedTask(String issueId, String key, String title, String jiraStatus,
                               Task.Eligibility eligibility, String routingReason) {
        return taskTasks.save(TaskFixture.task("MODBAR", issueId, key, title,
                "{\"key\": \"" + key + "\", \"status\": \"" + jiraStatus + "\"}", eligibility, routingReason));
    }

    private TaskAttempt seedAttempt(Task task, int ordinal, TaskAttempt.Kind kind,
                                     TaskAttempt.State state, String reason) {
        TaskAttempt attempt = new TaskAttempt(task.getId(), ordinal, kind,
                "smoke-" + task.getId() + "-" + ordinal, state);
        attempt.setResultReason(reason);
        return taskAttempts.save(attempt);
    }

    private void holdTheSlot(TaskAttempt attempt) {
        TaskInstallation installation = taskInstallations.findById(1).orElseThrow();
        installation.setActiveAttemptId(attempt.getId());
        taskInstallations.save(installation);
    }

    private PipelineExecution runningExecution() {
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        execution.setStatus(ExecutionStatus.RUNNING);
        return executions.save(execution);
    }

    @Test
    void myTasksRendersAvailableTriggeredAndTheWaitingQueue() {
        resetTaskData();
        seedTask("70001", "MODBAR-701", "Awaiting task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedTask("70002", "MODBAR-702", "Unsupported task", Task.Eligibility.INELIGIBLE,
                "Project MODBAR is not configured: no repository for it in config/developer-repositories.yaml");
        seedTask("70003", "MODBAR-703", "Reviewed task", "In Review", Task.Eligibility.ELIGIBLE,
                "repository mod-bar");
        Task queued = seedTask("70004", "MODBAR-704", "Queued task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(queued, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.RESERVED, null);
        Task stopped = seedTask("70005", "MODBAR-705", "Stopped task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution stoppedRun = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt stoppedAttempt = seedAttempt(stopped, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "VERIFICATION_FAILED: 2 tests in error");
        stoppedAttempt.setExecutionId(stoppedRun.getId());
        taskAttempts.save(stoppedAttempt);
        // An attempt that stopped before it got a run has no run page to open.
        Task notStarted = seedTask("70014", "MODBAR-714", "Stopped before its run",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(notStarted, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "DEADLINE_EXCEEDED: the attempt waited too long in the queue");
        Task delivered = seedTask("70006", "MODBAR-706", "Delivered task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        TaskAttempt ready = seedAttempt(delivered, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.PR_READY, null);
        ready.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        taskAttempts.save(ready);
        Task held = seedTask("70007", "MODBAR-707", "Held task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        holdTheSlot(seedAttempt(held, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.NEEDS_INSPECTION, null));
        Task byKey = seedTask("70008", "MODBAR-708", "Task added by key",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        byKey.setAddedByKey(true);
        taskTasks.save(byKey);
        TaskAttempt byKeyAttempt = seedAttempt(byKey, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.RESERVED, null);
        byKeyAttempt.setAdmittedVia(TaskAttempt.AdmittedVia.BY_KEY);
        taskAttempts.save(byKeyAttempt);
        Task live = seedTask("70009", "MODBAR-709", "Live task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = runningExecution();
        TaskAttempt liveAttempt = seedAttempt(live, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        taskAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", Map.of());
        Task draft = seedTask("70010", "MODBAR-710", "Draft task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        TaskAttempt draftAttempt = seedAttempt(draft, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.PR_READY, null);
        draftAttempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/8");
        draftAttempt.setDraftReasons("[\"1 acceptance criterion is not met.\"]");
        taskAttempts.save(draftAttempt);
        Task added = seedTask("70013", "MODBAR-713", "Added and not run yet",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        added.setAddedByKey(true);
        taskTasks.save(added);
        Task awaiting = taskTasks.findAll().stream().filter(task -> task.getDisplayKey().equals("MODBAR-701"))
                .findFirst().orElseThrow();
        Task unsupported = taskTasks.findAll().stream().filter(task -> task.getDisplayKey().equals("MODBAR-702"))
                .findFirst().orElseThrow();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        // Tabs in the header row; the configuration is a tab, not a button.
        assertThat(body)
                .contains("aria-current=\"page\">My tasks</a>", "href=\"/flows/dev-factory?view=definition\"",
                        "href=\"/developer/configuration\"", ">Configuration</a>")
                .doesNotContain("Check configuration", "My Jira tasks", "What is in this list",
                        "assignee = &quot;Jane Developer&quot;");
        // The running task: one strip with its state first, then the task, then the shortcut to its run.
        assertThat(body)
                .containsPattern("<span class=\"progress-strip-state\">In progress</span>\\s*"
                        + "<a class=\"key-link ext-link\" href=\"https://jira.example.org/browse/MODBAR-709\">MODBAR-709</a>"
                        + "\\s*<span class=\"progress-strip-title\" title=\"Live task\">Live task</span>")
                .containsPattern("<a class=\"btn btn-secondary btn-sm btn-col\" href=\"/executions/"
                        + liveExecution.getId() + "\">Open run</a>");
        // Ready to run: the ways to get tasks in its header, the rows without frame or header row.
        String readyList = body.substring(body.indexOf("id=\"available-title\""), body.indexOf("id=\"triggered-title\""));
        assertThat(readyList)
                .containsPattern("Ready to run <span class=\"dev-count\"\\s*>3</span>")
                .contains("action=\"/flows/dev-factory/add-by-key\"", "placeholder=\"Add by key, for example MGRAPPS-101\"",
                        ">Add</button>", "action=\"/flows/dev-factory/scan\"", ">Scan Jira</button>",
                        "title=\"Not scanned yet\"", "action=\"/tasks/run-selected\"",
                        "<a class=\"key-link ext-link\" href=\"https://jira.example.org/browse/MODBAR-701\">MODBAR-701</a>",
                        "<span>Jira: Open</span>", "<span>Jira: In Review</span>", "<span>mod-bar</span>",
                        "aria-label=\"Select MODBAR-701\"", ">Effort</span>")
                .contains("data-run-selected disabled>Run</button>")
                .doesNotContain("data-status-chip", "task-grid", "data-select-all", "Select MODBAR-702",
                        "Add to list", "Not in your list?", "added by key", "fix-dialog");
        // Tasks that cannot run: one muted line per reason under the list, no disclosure.
        assertThat(readyList)
                .contains("<p class=\"dev-list-note\">1 task cannot run here: MODBAR-702. Project MODBAR has no"
                        + " repository in config/developer-repositories.yaml.</p>")
                .doesNotContain("cannot run here</summary>");
        // The effort choice and Remove sit in the fixed columns of the row; the effort says what it means.
        assertThat(readyList)
                .containsPattern("<div class=\"row-cluster row-cluster-ready\">\\s*<div class=\"run-setting\"\\s+"
                        + "title=\"Effort is how long the model thinks at each step: higher is slower and costs more.\">");
        // Run: one Model for the run, the ready models only, each with its levels for the page script.
        String run = body.substring(body.indexOf("action=\"/tasks/run-selected\""), body.indexOf("data-run-selected"));
        assertThat(run).contains("<span>Model</span>", "name=\"modelId\"", "data-model-select")
                .containsPattern("<option value=\"codemie-sonnet-5.5\"(?=[^>]*selected=\"selected\")"
                        + "(?=[^>]*data-efforts=\"low medium high xhigh max\")(?=[^>]*data-default-effort=\"high\")")
                .containsPattern("<option value=\"glm-5.3\"(?=[^>]*data-efforts=\"low high max\")"
                        + "(?=[^>]*data-default-effort=\"max\")")
                .doesNotContain("value=\"anthropic-sonnet-5.5\"", "value=\"codemie-gemini-3.8-flash\"");
        // Effort on each row that can run: radio buttons of the selected model, its default checked.
        assertThat(body)
                .containsPattern("role=\"radiogroup\"[^>]*aria-label=\"Effort for MODBAR-701\"")
                .containsPattern("<input type=\"radio\" name=\"effort-" + awaiting.getId()
                        + "\" value=\"high\" checked=\"checked\">\\s*<span>high</span>")
                .containsPattern("<input type=\"radio\" name=\"effort-" + awaiting.getId()
                        + "\" value=\"xhigh\">\\s*<span>xhigh</span>")
                .doesNotContain("name=\"effort-" + unsupported.getId() + "\"");
        // A task added by key and not run yet can leave the list again.
        assertThat(readyList).containsPattern("<button type=\"submit\" class=\"btn btn-secondary btn-sm btn-col\"\\s+"
                + "formaction=\"/tasks/" + added.getId() + "/remove-from-list\"");
        // Runs: title, key, cost and age, a chip, and one action per row.
        String triggered = body.substring(body.indexOf("id=\"triggered-title\""));
        assertThat(triggered)
                .contains("Queued", "next in line", "2nd in line", "Verification failed: 2 tests in error",
                        "class=\"chip chip-danger\">Failed</span>", "class=\"chip chip-warning\">Needs inspection</span>",
                        "class=\"chip chip-info\">In progress</span>", "outcome not confirmed")
                .containsPattern("<a class=\"chip chip-success ext-link\" href=\"https://github.com/folio-org/mod-bar/pull/7\""
                        + ">PR #7</a>")
                .containsPattern("<a class=\"chip chip-neutral ext-link\" href=\"https://github.com/folio-org/mod-bar/pull/8\""
                        + ">Draft PR #8</a>")
                .containsPattern("<a class=\"dev-row-title\" href=\"/tasks/" + stopped.getId()
                        + "\"\\s+title=\"Stopped task\">Stopped task</a>")
                // A failed task opens its run; one that never got a run offers Retry.
                .containsPattern("<a class=\"btn btn-secondary btn-sm btn-col\" href=\"/executions/" + stoppedRun.getId()
                        + "\"\\s+aria-label=\"Open run MODBAR-705\">Open run</a>")
                .containsPattern("<a class=\"btn btn-secondary btn-sm btn-col\" href=\"/tasks/" + notStarted.getId()
                        + "#retry\"\\s+aria-label=\"Retry MODBAR-714\">Retry</a>")
                .doesNotContain("aria-label=\"Retry MODBAR-705\"")
                // Amount, chip and button are the fixed columns of the row.
                .containsPattern("<div class=\"row-cluster\">\\s*<span class=\"row-amount\"[^>]*>[^<]*</span>\\s*"
                        + "<span class=\"chip chip-danger\">Failed</span>")
                .containsPattern("href=\"/executions/" + liveExecution.getId() + "\"\\s+aria-label=\"Open run MODBAR-709\">Open run</a>")
                .containsPattern("<form method=\"post\"\\s+action=\"/tasks/" + held.getId()
                        + "/attempts/[0-9a-f-]+/mark-stopped\">\\s*<input type=\"hidden\" name=\"returnTo\" value=\"flow\">"
                        + "\\s*<button type=\"submit\" class=\"btn btn-secondary btn-sm\"[^>]*aria-label=\"Mark as stopped: MODBAR-707\"")
                .contains("Queue is waiting.", "MODBAR-707 needs inspection", "data-confirm-title=\"Mark MODBAR-707 as stopped?\"",
                        "The task becomes Failed, its cost so far stays")
                .contains("Queued tasks are not starting.", "FACTORY_TASKS_EXECUTION_ENABLED=true")
                .doesNotContain("task-grid", "Triggered", " working · ", ">Retry</button>", "Working");
        assertThat(body).contains("data-selection=\"taskId\"", "data-run-selected", "data-poll-notice",
                        "data-poll-url=\"/tasks/poll\"", "data-poll-fields=\"revision\"", "data-poll-baseline=")
                .doesNotContain("Trigger flow", "Outside current filter", "Enable automatic admission", "Withdrawn",
                        "Sprint", "run-by-key", "Max turns", "name=\"maxTurns\"");
        assertThat(body).doesNotContain(ZAI_KEY, PROXY_TOKEN, "not configured)");
        assertThat(rest.get().uri("/tasks").retrieve().toEntity(String.class).getBody()).contains("Ready to run");
    }

    @Test
    void myTasksShowsARunThatAskedAsNeedsYourAnswerWithAnswer() {
        resetTaskData();
        Task asked = seedTask("70020", "MODBAR-720", "Asking task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(asked, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API should change?");

        String body = assertRendered("/flows/dev-factory", "Runs");

        String runs = body.substring(body.indexOf("id=\"triggered-title\""));
        assertThat(runs)
                .contains("<span class=\"chip chip-warning\">Needs your answer</span>")
                .containsPattern("href=\"/tasks/" + asked.getId() + "#answer\"\\s+aria-label=\"Answer MODBAR-720\""
                        + "\\s*>Answer</a>")
                .doesNotContain("Which API should change?", ">Retry</a>");
    }

    @Test
    void myTasksShowsNoRunControlsWhenNoTaskCanRun() {
        resetTaskData();
        for (int i = 1; i <= 3; i++) {
            seedTask("7010" + i, "MODBAR-71" + i, "Unsupported task " + i, Task.Eligibility.INELIGIBLE,
                    "Project MODBAR is not configured: no repository for it in config/developer-repositories.yaml");
        }

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        assertThat(body)
                .containsPattern("Ready to run <span class=\"dev-count\"\\s*>0</span>")
                .contains("3 tasks cannot run here: MODBAR-711, MODBAR-712, MODBAR-713. Project"
                        + " MODBAR has no repository in config/developer-repositories.yaml.")
                // Nothing to run: no Model choice and no disabled Run button under the empty list.
                .doesNotContain("data-run-selected", "data-model-select", "name=\"modelId\"");

        seedTask("70104", "MODBAR-714", "Eligible task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        assertThat(assertRendered("/flows/dev-factory", "Ready to run"))
                .contains("data-run-selected disabled>Run</button>");
    }

    @Test
    void aModelWithoutItsSecretIsNotOfferedAndOneReadyModelHidesTheList() {
        resetTaskData();
        Task eligible = seedTask("70201", "MODBAR-761", "Eligible task", Task.Eligibility.ELIGIBLE,
                "repository mod-bar");
        // The same models file, but only FACTORY_ZAI_API_KEY is set, and glm-5.3-flash is the only other model.
        var glmOnly = new ModelCatalog(new ModelCatalog.Properties(modelsFile.models().stream()
                .filter(model -> model.id().startsWith("glm-5.3")).toList(), "glm-5.3"), "claude-code",
                name -> "FACTORY_ZAI_API_KEY".equals(name) ? ZAI_KEY : null);
        var withoutProxy = new TaskRunSettings(codingRuntime, glmOnly);
        doAnswer(call -> withoutProxy.form()).when(runSettings).form();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        assertThat(body).containsPattern("<option value=\"glm-5.3\"[^>]*selected=\"selected\"")
                .contains("value=\"glm-5.3-flash\"")
                .doesNotContain("value=\"codemie-sonnet-5.5\"", "not configured)")
                .containsPattern("<input type=\"radio\" name=\"effort-" + eligible.getId()
                        + "\" value=\"max\" checked=\"checked\">");

        var oneModel = new ModelCatalog(new ModelCatalog.Properties(modelsFile.models().stream()
                .filter(model -> model.id().equals("glm-5.3")).toList(), null), "claude-code",
                name -> "FACTORY_ZAI_API_KEY".equals(name) ? ZAI_KEY : null);
        var single = new TaskRunSettings(codingRuntime, oneModel);
        doAnswer(call -> single.form()).when(runSettings).form();
        assertThat(assertRendered("/flows/dev-factory", "Ready to run"))
                .contains("<input type=\"hidden\" name=\"modelId\" value=\"glm-5.3\">")
                .doesNotContain("data-model-select", "<span>Model</span>");

        var none = new TaskRunSettings(codingRuntime, new ModelCatalog(modelsFile, "claude-code", name -> null));
        doAnswer(call -> none.form()).when(runSettings).form();
        assertThat(assertRendered("/flows/dev-factory", "Ready to run"))
                .contains("No model can run now. The Configuration tab lists the models in config/developer-models.yaml")
                .doesNotContain("name=\"modelId\"", "name=\"effort-");
    }

    @Test
    void piRetryFormHasOnlyTheInstructionsAndNoModelChoice() {
        resetTaskData();
        Task stopped = seedTask("70202", "MODBAR-762", "Stopped task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(stopped, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "VERIFICATION_FAILED: 2 tests in error");
        // With the Pi harness there is no model choice, so the form has no Model and no Effort.
        doReturn(null).when(runSettings).form();

        String body = assertRendered("/tasks/" + stopped.getId(), "Failed");
        assertThat(body).containsPattern("<section class=\"dev-section\" id=\"retry\"")
                .contains("What should the agent do differently? (optional)", "name=\"instructions\"",
                        ">Start attempt 2</button>")
                .doesNotContain("name=\"modelId\"", "name=\"effort\"", "data-effort-toggle");
        assertThat(assertRendered("/flows/dev-factory", "Ready to run"))
                .doesNotContain("name=\"modelId\"", "data-effort-toggle", ">Effort</span>");
    }

    @Test
    void myTasksShowsEmptyStatesAndTheLastScanProblem() {
        resetTaskData();

        String body = assertRendered("/flows/dev-factory", "Press Scan Jira to load the tasks assigned to you.");
        assertThat(body).contains("title=\"Not scanned yet\"", "Nothing has run yet.");

        TaskInstallation installation = taskInstallations.findById(1).orElseThrow();
        installation.setSyncProblem("search page 2 failed");
        taskInstallations.save(installation);
        assertThat(assertRendered("/flows/dev-factory", "Last scan failed: search page 2 failed"))
                .contains("Ready to run");
    }

    @Test
    void developerFlowDefinitionTabHasNoJsonTrigger() {
        String body = assertRendered("/flows/dev-factory?view=definition", "Steps <span class=\"dev-count\">10</span>");
        assertThat(body).contains("aria-current=\"page\">Definition</a>", ">Configuration</a>",
                        ">1 · Read task</div>", ">10 · PR follow up</div>",
                        "dev_decision_request.md → no artifacts", "no artifacts → dev_pr_state.json, dev_pr_request.json",
                        "Worker dev-pr-follow-up, can pause and go on where it stopped", ">Needs issueKey</div>",
                        "Step retries: up to 2, waiting 30 s, then 2 min.")
                .doesNotContain("trigger-dialog", "Trigger flow", "class=\"stepper\"", "30120300",
                        "dev-pr-follow-up, resumable", "Agent step");
        assertThat(assertRendered("/flows/test-factory", "Steps")).contains("Trigger flow");
    }

    @Test
    void taskScreensEscapeIssueText() {
        resetTaskData();
        Task hostile = seedTask("70011", "MODBAR-711",
                "Fix <routing> & permissions <script>alert(1)</script>",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        String longTitle = "Modular routing regression " + "x".repeat(300);
        Task longTitled = seedTask("70012", "MODBAR-712", longTitle,
                Task.Eligibility.ELIGIBLE, "repository mod-bar");

        String list = assertRendered("/flows/dev-factory", "Fix &lt;routing&gt; &amp; permissions");
        assertThat(list).doesNotContain("<script>alert(1)</script>").contains(longTitle);

        String detail = assertRendered("/tasks/" + hostile.getId(), "Fix &lt;routing&gt; &amp; permissions");
        assertThat(detail).doesNotContain("<script>alert(1)</script>").contains("MODBAR-711", "Not started");

        assertThat(assertRendered("/tasks/" + longTitled.getId(), longTitle)).contains(longTitle);
    }

    @Test
    void taskSummaryShowsResultCostAttemptsAndRetry() {
        resetTaskData();
        Task stopped = seedTask("70021", "MODBAR-721", "Stopped task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt attempt = seedAttempt(stopped, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.STOPPED, "VERIFICATION_FAILED: 2 tests in error");
        attempt.setExecutionId(execution.getId());
        attempt.setRunSettings("{\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\",\"model\":\"glm-5.3\","
                + "\"endpointOrigin\":\"https://api.z.ai\",\"effort\":\"low\",\"maxTurns\":77,"
                + "\"instructions\":\"Read the ADR through the wiki <REST> URL.\"}");
        taskAttempts.save(attempt);
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "model", "test-model",
                        "inputTokens", 100_000, "cacheReadTokens", 10_000, "cacheWriteTokens", 0,
                        "outputTokens", 10_000,
                        "rate", Map.of("currency", "USD", "source", "card",
                                "inputPerMillion", 0.15, "cacheReadPerMillion", 0.03, "outputPerMillion", 0.5)));

        String body = assertRendered("/tasks/" + stopped.getId(), "Failed");
        assertThat(body)
                .contains("<a href=\"/flows/dev-factory\">Developer Flow</a>",
                        "<span class=\"chip chip-danger\">Failed</span>",
                        "<p class=\"status-card-headline\">Attempt 1 failed</p>")
                .containsPattern("<span class=\"status-card-label\">Reason</span>\\s*"
                        + "<span>Verification failed: 2 tests in error.</span>")
                .contains("<span class=\"status-card-money\">$0.02</span>", "spent in 1 attempt",
                        "No pull request was created.")
                // Technical details are gone: the URL, the header and the status card say all they held.
                .doesNotContain("Technical details", "tech-disclosure", "Total tokens", "Cost and usage",
                        "dev-page-narrow")
                .containsPattern("<div class=\"dev-meta dev-meta-dots\">\\s*<a class=\"key-link ext-link\"[^>]*>MODBAR-721</a>"
                        + "\\s*<span>mod-bar</span>")
                .containsPattern("<a class=\"btn btn-secondary btn-sm\" href=\"/executions/" + execution.getId()
                        + "\">Open run</a>")
                .contains(">Start attempt 2</button>", "name=\"expectedVersion\"", "name=\"inputFingerprint\"",
                        "name=\"destinationFingerprint\"",
                        "A new attempt starts from a clean checkout and adds to the cost. Effort is how long the model"
                                + " thinks at each step: higher is slower and costs more.")
                // The next attempt is its own section; its text field is empty and has a name for screen readers.
                .containsPattern("<section class=\"dev-section\" id=\"retry\"")
                .containsPattern("<form class=\"dev-form\" method=\"post\"\\s+action=\"/tasks/"
                        + stopped.getId() + "/retry\"")
                .contains("<label for=\"retry-instructions\">What should the agent do differently? (optional)</label>",
                        "id=\"retry-instructions\" name=\"instructions\"", "maxlength=\"8000\"",
                        "placeholder=\"For example: change only FolioTenantApiClient and its test.\"></textarea>")
                .doesNotContain(">Read the ADR through the wiki &lt;REST&gt; URL.</textarea>", "value=\"77\"", "Max turns")
                // A new attempt starts from the configured model and effort, not from the previous attempt's.
                .containsPattern("<option value=\"codemie-sonnet-5.5\"[^>]*selected=\"selected\"")
                .doesNotContainPattern("<option value=\"glm-5.3\"[^>]*selected=\"selected\"")
                .contains("<span>Effort</span>", "<input type=\"radio\" name=\"effort\" value=\"high\" checked=\"checked\">")
                .contains("glm-5.3 · low · ", "&quot;Read the ADR through the wiki &lt;REST&gt; URL.&quot;")
                // Only the quote may end with an ellipsis on a narrow row; the facts before it stay whole.
                .contains("<span class=\"dev-row-quote\">&quot;Read the ADR through the wiki &lt;REST&gt; URL.&quot;"
                        + "</span></div>")
                .containsPattern("<div class=\"dev-row-meta dev-row-meta-dots dev-row-meta-quoted\" title=\"[^\"]*\">"
                        + "<span>glm-5.3 · low</span>")
                .doesNotContain(ZAI_KEY)
                .containsPattern("href=\"/executions/" + execution.getId() + "\"\\s+aria-label=\"Open run for attempt 1\""
                        + "\\s*>Open run</a>")
                .contains("Attempt 1</strong>", "<span>first run</span>");

        Task partial = seedTask("70022", "MODBAR-722", "Partial task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution partialExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt partialAttempt = seedAttempt(partial, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.STOPPED, "verification: incomplete");
        partialAttempt.setExecutionId(partialExecution.getId());
        taskAttempts.save(partialAttempt);
        auditLog.record(partialExecution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "model", "test-model",
                        "inputTokens", 50_000, "cacheReadTokens", 0, "cacheWriteTokens", 0,
                        "rate", Map.of("currency", "USD", "inputPerMillion", 0.15)));

        String partialBody = assertRendered("/tasks/" + partial.getId(), ">spent in 1 attempt</span>");
        // Why the amount may be too low is the title of the amount, not a visible note.
        assertThat(partialBody).doesNotContain("spent in 1 attempt, cost may be higher")
                // 50,000 tokens at $0.15 per million cost $0.0075: under one cent, never "$0.00" or "$0.01".
                .contains("<span class=\"status-card-money\" title=\"Incomplete: some usage has"
                        + " no reported tokens or rate yet. Cost per attempt is in Attempts.\">&lt;$0.01</span>");
        String costNote = "<span class=\"row-amount\"\\s+title=\"Some usage was not reported by the runtime or has no"
                + " price, so the real cost may be higher.\">&lt;\\$0.01</span>";
        assertThat(partialBody.substring(partialBody.indexOf("id=\"attempts-title\"")))
                .containsPattern(costNote).doesNotContain(">incomplete<");
        String partialList = assertRendered("/flows/dev-factory", "Ready to run");
        assertThat(partialList.substring(partialList.indexOf("id=\"triggered-title\"")))
                .contains("<span class=\"row-amount\" title=\"Some usage was not reported by the runtime or has"
                        + " no price, so the real cost may be higher.\">&lt;$0.01</span>")
                .doesNotContain(">incomplete<", ">cost may be higher<");

        Task live = seedTask("70023", "MODBAR-723", "Live task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution liveExecution = runningExecution();
        TaskAttempt liveAttempt = seedAttempt(live, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.RUNNING, null);
        liveAttempt.setExecutionId(liveExecution.getId());
        taskAttempts.save(liveAttempt);
        auditLog.record(liveExecution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement", "smoke",
                Map.of("attemptId", "a1", "round", -1, "activity", "coding_usage", "model", "test-model",
                        "inputTokens", 10_000, "cacheReadTokens", 0, "cacheWriteTokens", 0,
                        "rate", Map.of("currency", "USD", "inputPerMillion", 0.15)));
        auditLog.record(liveExecution.getId(), AuditEventType.STEP_STARTED, "implement", "smoke", Map.of());

        String liveBody = assertRendered("/tasks/" + live.getId(), "Implementing changes</p>");
        assertThat(liveBody)
                .contains("In progress", ">Open run</a>", "Includes live usage that can still change.")
                .doesNotContain("name=\"expectedVersion\"", "Implementing changes.");
        // The status card shows the time of the coding step against its own limit, as the run page does.
        assertThat(liveBody.substring(liveBody.indexOf("class=\"status-card-side\"")))
                .containsPattern("<span title=\"The whole attempt may take 1 h.\"><span\\s+class=\"status-card-money\""
                        + " data-elapsed-start=\"[^\"]+\">\\d+ s</span>\\s*"
                        + "<span class=\"status-card-note\">of 30 min</span></span>");
        // A running attempt's cost is "so far", the same as Working in Triggered.
        assertThat(liveBody.substring(liveBody.indexOf("id=\"attempts-title\"")))
                .containsPattern("<span class=\"row-amount\"\\s*>&lt;\\$0.01 so far</span>")
                .containsPattern("<span class=\"chip chip-info\"\\s+title=\"Implementing changes\">In progress</span>")
                .doesNotContain("cost may be higher", "cost unknown");

        // A paid start that stopped before the runtime reported any usage, for example on a Docker error:
        // there is no cost figure, so the note says the cost is unknown, not that it may be higher.
        Task unreported = seedTask("70024", "MODBAR-724", "Unreported task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution unreportedExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt unreportedAttempt = seedAttempt(unreported, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: the coding container did not start");
        unreportedAttempt.setExecutionId(unreportedExecution.getId());
        unreportedAttempt.setPaidStartAt(Instant.now());
        taskAttempts.save(unreportedAttempt);

        String unknownCost = "<span class=\"row-amount\"\\s+title=\"The runtime did not report usage,"
                + " or the usage has no price, so the cost is not known.\">cost unknown</span>";
        String unreportedBody = assertRendered("/tasks/" + unreported.getId(), "Failed");
        assertThat(unreportedBody.substring(unreportedBody.indexOf("id=\"attempts-title\"")))
                .containsPattern(unknownCost).doesNotContain("cost may be higher");
        String unreportedList = assertRendered("/flows/dev-factory", "Ready to run");
        String unreportedRow = unreportedList.substring(unreportedList.indexOf(">MODBAR-724</a>"));
        assertThat(unreportedRow.substring(0, unreportedRow.indexOf("class=\"dev-row-action\"")))
                .doesNotContain("cost may be higher", "cost unknown", "—");
    }

    @Test
    void taskPageShowsTheAgentsQuestionAndStartsTheNextAttemptWithTheAnswer() {
        resetTaskData();
        Task asked = seedTask("70025", "MODBAR-725", "Asking task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt attempt = seedAttempt(asked, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "clarify-implementation: " + TaskStops.DECISION_REASON + "Remove <old> routes too?");
        attempt.setExecutionId(execution.getId());
        taskAttempts.save(attempt);
        artifactStore.putMarkdown(execution.getId(), "dev_coding_outcome.json", """
                {"status":"NEEDS_DECISION","summary":"I read the code.","decision":{"kind":"PRODUCT_REQUIREMENTS",
                "question":"Remove <old> routes too?","options":["Only from now on.","Also the old ones."],
                "evidence":"removeRoutes runs only on a new event."},"metrics":{}}""", "implement");

        String body = assertRendered("/tasks/" + asked.getId(), "The agent's question");
        assertThat(body)
                .contains("<span class=\"chip chip-warning\">Needs your answer</span>",
                        "<p class=\"status-card-headline\">The agent stopped with a question</p>",
                        "<p class=\"answer-question\">Remove &lt;old&gt; routes too?</p>",
                        "removeRoutes runs only on a new event.",
                        "role=\"radiogroup\" aria-label=\"Your answer\"",
                        "<span id=\"answer-0-text\">Only from now on.</span>",
                        "<span id=\"answer-1-text\">Also the old ones.</span>",
                        "<label for=\"answer-own\">", "<span\n                        id=\"answer-own-text\">My own answer</span>",
                        "Anything to add for the agent? (optional)", "name=\"note\"",
                        ">Start attempt 2 with this answer</button>",
                        "A new attempt starts from a clean checkout and adds to the cost.")
                // Each answer is named by its text, not by its value.
                .contains("<label for=\"answer-0\">")
                .containsPattern("<input type=\"radio\" name=\"answer\" id=\"answer-0\" value=\"0\"\\s*"
                        + "aria-labelledby=\"answer-0-text\" checked=\"checked\">")
                .containsPattern("<input type=\"radio\" name=\"answer\" id=\"answer-own\" value=\"own\"\\s*"
                        + "aria-labelledby=\"answer-own-text\"\\s*>")
                .doesNotContain("id=\"retry\"", "What should the agent do differently?", "I read the code.");
        // The attempt row has the chip of the status card: the question of the latest attempt waits for an answer.
        // Its title is the question, without the words the flow stores before it.
        assertThat(body.substring(body.indexOf("id=\"attempts-title\"")))
                .contains("title=\"Remove &lt;old&gt; routes too?\">Needs your answer</span>")
                .doesNotContainIgnoringCase("human decision");
    }

    @Test
    void taskSummaryForPrReadyLinksThePullRequestAndItsFeedback() {
        resetTaskData();
        Task delivered = seedTask("70031", "MODBAR-731", "Delivered task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt attempt = seedAttempt(delivered, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/7");
        attempt.setExecutionId(execution.getId());
        taskAttempts.save(attempt);

        String body = assertRendered("/tasks/" + delivered.getId(),
                "<p class=\"status-card-headline\">Pull request #7 is ready for review</p>");
        // The chip opens the pull request; the secondary button opens the PR feedback block of the run page.
        assertThat(body)
                .contains("<a class=\"chip chip-success ext-link\" href=\"https://github.com/folio-org/mod-bar/pull/7\">"
                                + "PR #7</a>",
                        "class=\"btn btn-primary btn-sm\" href=\"https://github.com/folio-org/mod-bar/pull/7\">"
                                + "Open pull request #7</a>",
                        "class=\"btn btn-secondary btn-sm\" href=\"/executions/" + execution.getId()
                                + "#pr-feedback\">PR feedback</a>")
                .doesNotContain("Draft PR", "Factory opened it as a draft because:")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void theCostOfATaskIncludesItsPrFeedbackRounds() {
        resetTaskData();
        Task delivered = seedTask("70033", "MODBAR-733", "Task with a round",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt attempt = seedAttempt(delivered, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/9");
        attempt.setExecutionId(execution.getId());
        taskAttempts.save(attempt);
        // The first implementation and one PR feedback round, each $0.15.
        for (var coding : List.of(Map.entry("implement", -1), Map.entry("pr-follow-up", 1))) {
            auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, coding.getKey(), "smoke",
                    Map.of("attemptId", coding.getKey() + "-attempt", "round", coding.getValue(), "model", "test-model",
                            "inputTokens", 1_000_000, "cacheReadTokens", 0, "cacheWriteTokens", 0, "outputTokens", 0,
                            "rate", Map.of("currency", "USD", "source", "card", "inputPerMillion", 0.15,
                                    "cacheReadPerMillion", 0.03, "outputPerMillion", 0.5)));
        }

        String list = assertRendered("/flows/dev-factory", "Runs");
        assertThat(list.substring(list.indexOf(">MODBAR-733</a>")))
                .containsPattern("<span class=\"row-amount\"[^>]*>\\$0.30</span>");
        assertThat(assertRendered("/tasks/" + delivered.getId(), "Pull request #9"))
                .contains("<span class=\"status-card-money\">$0.30</span>");
    }

    @Test
    void theTaskPageRefreshesItselfFromItsOwnPoll() {
        resetTaskData();
        Task stopped = seedTask("70034", "MODBAR-734", "Polled task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(stopped, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "VERIFICATION_FAILED: 2 tests in error");

        String body = assertRendered("/tasks/" + stopped.getId(), "Failed");
        assertThat(body).contains("data-poll-fields=\"revision\"",
                "data-poll-url=\"/tasks/" + stopped.getId() + "/poll\"",
                "<p class=\"dev-poll-notice\" role=\"status\" data-poll-notice hidden>This task changed.");
        Matcher baseline = Pattern.compile("data-poll-baseline=\"([0-9a-f]+)\"").matcher(body);
        assertThat(baseline.find()).isTrue();
        // Nothing changed since the page was built: the poll gives the page's own baseline.
        assertThat(rest.get().uri("/tasks/" + stopped.getId() + "/poll").retrieve().toEntity(String.class).getBody())
                .isEqualTo("{\"revision\":\"" + baseline.group(1) + "\"}");
    }

    @Test
    void draftPrShowsItsReasonsOnTheTaskPageAndDraftOnTheRunPage() {
        resetTaskData();
        Task delivered = seedTask("70032", "MODBAR-732", "Draft task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        // A finished run, so the run page shows its result sentence instead of a live stage.
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        TaskAttempt attempt = seedAttempt(delivered, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/8");
        attempt.setDraftReasons("[\"1 acceptance criterion is not met.\","
                + "\"The agent made assumptions that a reviewer should confirm.\"]");
        attempt.setExecutionId(execution.getId());
        taskAttempts.save(attempt);
        artifactStore.putMarkdown(execution.getId(), "dev_delivery.json",
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/folio-org/mod-bar/pull/8\","
                        + "\"pullRequestDraft\":true,\"draftReasons\":[\"1 acceptance criterion is not met.\"]}",
                "publish");

        String body = assertRendered("/tasks/" + delivered.getId(),
                "<p class=\"status-card-headline\">Pull request #8 is open as a draft</p>");
        assertThat(body)
                .contains("<a class=\"chip chip-neutral ext-link\" href=\"https://github.com/folio-org/mod-bar/pull/8\">"
                                + "Draft PR #8</a>",
                        "Factory opened it as a draft because:", "<li>1 acceptance criterion is not met.</li>",
                        "<li>The agent made assumptions that a reviewer should confirm.</li>",
                        "Pull request #8 opened as a draft",
                        "href=\"https://github.com/folio-org/mod-bar/pull/8\">Open pull request #8</a>")
                .doesNotContain("chip-success", "is ready for review");
        // The Attempts row marks the draft with the chip of the status card.
        assertThat(body.substring(body.indexOf("id=\"attempts-title\"")))
                .containsPattern("<a class=\"chip chip-neutral ext-link\"\\s+href=\"https://github.com/folio-org/mod-bar/pull/8\""
                        + " title=\"Pull request #8 opened as a draft\">Draft PR #8</a>")
                .contains(">Open run</a>");

        String run = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(run)
                .contains("<a class=\"chip chip-neutral ext-link\" href=\"https://github.com/folio-org/mod-bar/pull/8\">"
                        + "Draft PR #8</a>", "<p class=\"status-card-headline\">Pull request #8 is open as a draft</p>",
                        // The run page lists why the pull request is a draft, and its meta line names the
                        // repository as the task page does.
                        "Factory opened it as a draft because:", "<li>1 acceptance criterion is not met.</li>",
                        "<span>mod-bar</span>")
                .containsPattern("data-state=\"done\">\\s*<span class=\"run-step-title\">4 · Create pull request</span>"
                        + "\\s*<span class=\"run-step-state\"><span>Passed</span> · <span>draft</span>")
                .doesNotContain(">PR ready</span>", "is ready for review");
    }

    @Test
    void taskSummaryOffersMarkAsStoppedOnlyForAHeldTask() {
        resetTaskData();
        Task held = seedTask("70061", "MODBAR-761", "Held task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        TaskAttempt heldAttempt = seedAttempt(held, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.NEEDS_INSPECTION, null);
        holdTheSlot(heldAttempt);

        assertThat(assertRendered("/tasks/" + held.getId(), "Needs inspection"))
                .contains("<p class=\"status-card-headline\">Factory could not confirm how the run ended</p>", "Mark as stopped…",
                        "/attempts/" + heldAttempt.getId() + "/mark-stopped", "data-confirm-style=\"compact\"")
                .doesNotContain("name=\"expectedVersion\"");
    }

    @Test
    void taskSummaryRendersFullAttemptHistoryNewestFirst() {
        resetTaskData();
        Task retried = seedTask("70041", "MODBAR-741", "Retried task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution first = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        PipelineExecution second = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        seedAttempt(retried, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.WITHDRAWN,
                "task left the My tasks scope before start");
        TaskAttempt retry = seedAttempt(retried, 2, TaskAttempt.Kind.RETRY,
                TaskAttempt.State.STOPPED, "verification: first failure");
        retry.setExecutionId(first.getId());
        taskAttempts.save(retry);
        TaskAttempt fresh = seedAttempt(retried, 3, TaskAttempt.Kind.RETRY,
                TaskAttempt.State.STOPPED, "verification: second failure");
        fresh.setExecutionId(second.getId());
        taskAttempts.save(fresh);

        String body = assertRendered("/tasks/" + retried.getId(), "Attempts");
        assertThat(body)
                .contains("Attempt 3</strong>", "<span>retry</span>", "<span>first run</span>", "Withdrawn before it started",
                        "Verification failed: first failure", "Verification failed: second failure")
                .contains("Failed");
        String attemptsTable = body.substring(body.indexOf("id=\"attempts-title\""));
        // A clear Open run button replaces the small "run 1234…" link.
        assertThat(attemptsTable)
                .containsPattern("<a class=\"btn btn-secondary btn-sm btn-col\"\\s+href=\"/executions/" + first.getId()
                        + "\"\\s+aria-label=\"Open run for attempt 2\">Open run</a>")
                .containsPattern("<a class=\"btn btn-secondary btn-sm btn-col\"\\s+href=\"/executions/" + second.getId()
                        + "\"\\s+aria-label=\"Open run for attempt 3\">Open run</a>")
                .doesNotContainPattern(">run [0-9a-f]{4}…</a>");
        // Newest first: a new attempt is added at the top.
        assertThat(attemptsTable.indexOf("Attempt 3</strong>")).isLessThan(attemptsTable.indexOf("Attempt 2</strong>"));
        assertThat(attemptsTable.indexOf("Attempt 2</strong>")).isLessThan(attemptsTable.indexOf("Attempt 1</strong>"));
    }

    @Test
    void runPageLinksBackToItsTaskAndShowsTheAttemptState() {
        resetTaskData();
        Task task = seedTask("70071", "MODBAR-771", "Running task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution execution = runningExecution();
        TaskAttempt attempt = seedAttempt(task, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.RUNNING, null);
        attempt.setExecutionId(execution.getId());
        taskAttempts.save(attempt);

        String body = assertRendered("/executions/" + execution.getId(), "Activity");
        assertThat(header(body))
                .contains(">Running task</h1>", "href=\"/flows/dev-factory\">Developer Flow</a>",
                        "href=\"/tasks/" + task.getId() + "\">MODBAR-771</a>", "<span>Attempt 1</span>",
                        "<a class=\"key-link ext-link\" href=\"https://jira.example.org/browse/MODBAR-771\">MODBAR-771</a>")
                .doesNotContain("chip");
        assertThat(body)
                .contains("<title>MODBAR-771 · attempt 1 — AI FOLIO</title>", "class=\"chip chip-info\">In progress</span>",
                        "data-poll-url=\"/executions/" + execution.getId() + "/poll\"",
                        "<p class=\"dev-poll-notice\" role=\"status\" data-poll-notice hidden>")
                .containsPattern("href=\"/flows\"[^>]*aria-current=\"page\"")
                .doesNotContain("chip-status-RUNNING", "Engine steps", "Runtime and usage", "Now · updates live");
        // Right after the start: its own headline, the amount from the first render, the time alone.
        assertThat(body.substring(body.indexOf("class=\"status-card\"")))
                .contains("<p class=\"status-card-headline\">Starting the run</p>")
                .containsPattern("<span class=\"status-card-money\">\\$0.00</span>\\s*"
                        + "<span class=\"status-card-note\">so far</span>")
                .containsPattern("<span><span\\s+class=\"status-card-money\" data-elapsed-start=\"[^\"]+\">\\d+ s</span>"
                        + "\\s*</span>");
        // While the coding step works, the time is that step's, against the step's own limit.
        auditLog.record(execution.getId(), AuditEventType.STEP_STARTED, "implement", "smoke", Map.of());
        String coding = assertRendered("/executions/" + execution.getId(), "Implementing changes</p>");
        assertThat(coding.substring(coding.indexOf("class=\"status-card-side\"")))
                .containsPattern("<span title=\"The whole attempt may take 1 h.\"><span\\s+class=\"status-card-money\""
                        + " data-elapsed-start=\"[^\"]+\">\\d+ s</span>\\s*"
                        + "<span class=\"status-card-note\">of 30 min</span></span>");
    }

    /** The {@code pageTime} fragment of a time, as the page renders it. */
    private static String timeTag(PageTime time) {
        return "<time datetime=\"" + time.datetime() + "\" data-local=\"" + time.local() + "\" title=\""
                + time.title() + "\">" + time.text() + "</time>";
    }

    private String assertRendered(String path, String marker) {
        ResponseEntity<String> response = rest.get().uri(path).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Every page names its icon, so a browser does not ask for /favicon.ico, a 404 on the first page it opens.
        assertThat(response.getBody())
                .contains("app-shell", "<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,")
                .contains(marker);
        return response.getBody();
    }
}
