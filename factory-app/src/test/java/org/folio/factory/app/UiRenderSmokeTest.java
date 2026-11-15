package org.folio.factory.app;

import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.developer.ModelChecks;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.tasks.TaskFixture;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.RunState;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private static final String LONG_NAME_THEN_PUNCTUATION = "[A-Z][A-Z0-9_]{11,}(</code>)?[.,;:)]";

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

    @Autowired
    private JdbcClient jdbc;

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
                .contains("Runs started per day, by state", "<h2 class=\"card-title\">Runs by state</h2>",
                        "<h2 class=\"card-title\">Runs per flow</h2>");
        // One tile per state of the Executions Status filter, in its order and words, after Total runs; each opens
        // the list with that state and the chosen period. The page says what the counts count.
        var tiles = Pattern.compile("<a class=\"kpi\" href=\"([^\"]+)\">\\s*<span class=\"kpi-value\">\\d+</span>\\s*"
                + "<span class=\"kpi-label\">([^<]+)</span>").matcher(body).results()
                .map(tile -> tile.group(2) + " " + tile.group(1).replace("&amp;", "&")).toList();
        var expectedTiles = new java.util.ArrayList<>(List.of("Total runs /executions?days=14"));
        for (var state : RunState.values()) {
            expectedTiles.add(state.getLabel() + " /executions?status=" + state.name() + "&days=14");
        }
        assertThat(tiles).isEqualTo(expectedTiles);
        assertThat(body.indexOf("Cancelled or rejected</span>")).isLessThan(body.indexOf(">Execution engine<"));
        assertThat(text(body)).contains("What the runs started in the chosen period came to. Runs, tokens, step"
                + " failures and connector actions count only those runs");
        // Runs, reviews and time ranges in plain words, as on the other pages.
        assertThat(body).contains("<span class=\"kpi-label\">Total runs</span>", "<h2 class=\"card-title\">Reviews</h2>",
                        "<dt>Waiting now</dt>", "<dt>Longest wait now</dt>", "<dt>Decided in this period</dt>",
                        "<dt>Average time to a decision</dt>", ">24 hours</a>", "aria-current=\"page\">14 days</a>");
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
                .contains("class=\"btn btn-secondary\" data-confirm-cancel>Cancel</button>");
    }

    @Test
    void sessionsAreTrackedByCookieOnlySoRedirectsCarryNoSessionId() {
        assertThat(servletContext.getEffectiveSessionTrackingModes()).containsExactly(SessionTrackingMode.COOKIE);
    }

    @Test
    void pageLinksCarryAContentHashAndHashedFilesAreCachedForAYear() {
        ResponseEntity<String> page = rest.get().uri("/flows/dev-factory").retrieve().toEntity(String.class);
        String body = page.getBody();
        // A page is never stored, so the Back button loads it again and cannot repeat a one-time notice.
        assertThat(page.getHeaders().getCacheControl()).as("HTML pages are never stored").isEqualTo("no-store");
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
                        "1 h each for the first build, the coding and the checks",
                        "FACTORY_DEV_FACTORY_TIMEOUT_SECONDS", ">Time per attempt</dt>", "2 h from Prepare task",
                        "FACTORY_TASKS_EXECUTION_DEADLINE", ">Agent turns</dt>", "FACTORY_CLAUDE_CODE_MAX_TURNS",
                        ">Coding image</dt>", "FACTORY_CLAUDE_CODE_IMAGE")
                // Name, value and the variable that sets it are three cells.
                .containsPattern("<dt>Time per step</dt>\\s*<dd>1 h each for the first build[^<]*</dd>\\s*"
                        + "<dd class=\"config-variable\"><code>FACTORY_DEV_FACTORY_TIMEOUT_SECONDS</code></dd>")
                .containsPattern("<dt>Search</dt>\\s*<dd><code>[^<]+</code>[\\s\\S]*?</dd>\\s*"
                        + "<dd class=\"config-variable\"></dd>");
        assertThat(body).contains(">Tasks come from</h2>", "jira.example.org", "Jane Developer",
                        "assignee = &quot;Jane Developer&quot; AND statusCategory != Done ORDER BY updated DESC",
                        "href=\"https://jira.example.org/issues/?jql=", ">Jira sign-in</dt>");
        assertThat(body).doesNotContainPattern(LONG_NAME_THEN_PUNCTUATION);
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
    void aDeveloperRunWaitingForPrCommentsIsNamedByItsTaskAndOpensItsRunPage() {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.9.0", "{\"issueKey\":\"MGRAPPS-21\"}"));
        reviews.save(new HitlReview(execution.getId(), "pr-feedback", 8,
                "{\"title\":\"PR feedback\",\"flowId\":\"dev-factory\"}"));
        var gate = executions.save(new PipelineExecution("test-factory", "1", "{\"issueKey\":\"MGRAPPS-22\"}"));
        var qaReview = reviews.save(new HitlReview(gate.getId(), "qa-gate-1", 2,
                "{\"title\":\"QA review: manual test cases\",\"flowId\":\"test-factory\"}"));

        String body = assertRendered("/reviews", "Steps that wait for a person's decision, and the decisions made.");

        assertThat(body).contains("<td>Waiting for PR comments: MGRAPPS-21</td>",
                        "href=\"/executions/" + execution.getId() + "#pr-feedback\">Open run</a>",
                        "<td>QA review: manual test cases</td>", "href=\"/reviews/" + qaReview.getId() + "\">Review</a>")
                .doesNotContain("<td>PR feedback</td>", "MGRAPPS-22");
        // Each flow by its name and each gate in words, on one line; the action sits at the right edge of the row.
        assertThat(body).contains("<td class=\"nowrap\">Developer Flow</td>", "<td class=\"nowrap\">PR feedback</td>",
                        "<td class=\"nowrap\">Test Factory</td>", "<td class=\"nowrap\">QA gate 1</td>",
                        "<td class=\"cell-action\">", "<th class=\"cell-action\">")
                .doesNotContain(">dev-factory<", ">pr-feedback<", ">qa-gate-1<");
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
                .doesNotContain("<th>Execution</th>", ">FAILED_ESCALATED<");
        // The period of a Dashboard tile is a filter of its own, Started.
        assertThat(assertRendered("/executions?flow=test-factory&days=7", "Executions"))
                .contains("<label class=\"label\" for=\"filter-started\">Started</label>",
                        "<option value=\"\">Any time</option>", "<option value=\"1\">In the last 24 hours</option>",
                        "<option value=\"7\" selected=\"selected\">In the last 7 days</option>",
                        "<a class=\"table-link\" href=\"/executions/" + execution.getId() + "\">");
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
                // Only the title wraps; the decision and its reviewer stay on one line.
                .containsPattern("<td class=\"nowrap\">\\s*<span>AMEND</span>")
                // Each time is short UTC text with its instant, which time.js turns into the browser's local time.
                .contains(timeTag(PageTime.day(stored.getCreatedAt())), timeTag(PageTime.day(stored.getDecidedAt())));
    }

    @Test
    void promptsListShowsReadOnlyView() {
        String body = assertRendered("/prompts", "Bundled prompt templates");
        // The View action sits at the right edge of its row.
        assertThat(body).contains(">View<", "<th class=\"cell-action\">", "<td class=\"cell-action\">");
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
        assertThat(body).contains("Not configured for Test Factory sync: set FACTORY_CONNECTORS_JIRA_EMAIL",
                        "FACTORY_CONNECTORS_JIRA_API_TOKEN to turn it on.",
                        "My tasks reads Jira with FACTORY_CONNECTORS_JIRA_BASE_URL alone",
                        "href=\"/developer/configuration\">Developer Flow configuration</a>",
                        "href=\"/flows/dev-factory?view=definition\">dev-factory</a>",
                        "href=\"/flows/test-factory\">test-factory</a>");
        // A long variable name breaks anywhere in a narrow card, so no full stop or bracket follows one.
        assertThat(body).doesNotContainPattern(LONG_NAME_THEN_PUNCTUATION);
        // The Actuator endpoints are a list of links that says what they are, not tabs over an empty panel.
        assertThat(body).contains("Raw health and metrics endpoints of the app",
                        "<dt>Health</dt><dd><a class=\"dev-link\" href=\"/actuator/health\">/actuator/health</a></dd>");
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
                .doesNotContain(">manual<", ">jira.issue.transitioned<");
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
                .doesNotContain("class=\"stepper\"", ">Pipeline<", ">AGENT<", ">HITL_GATE<",
                        "Agent step", "Any payload", ">manual<");
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
        assertThat(statusCard(assertRendered("/executions/" + execution.getId(), "Developer Flow")))
                .contains("Implementing changes Retry pending Starting build hit a network/download failure");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.FAILED_ESCALATED);
        executions.save(execution);
        assertThat(statusCard(assertRendered("/executions/" + execution.getId(), "Developer Flow")))
                .contains("This run failed Reason Starting build hit a network/download failure")
                .doesNotContain("Retry pending");

        execution = executions.findById(execution.getId()).orElseThrow();
        execution.setStatus(ExecutionStatus.RUNNING);
        executions.save(execution);
        // Running again after the failure: the card says what the step retries after, until coding starts.
        assertThat(statusCard(assertRendered("/executions/" + execution.getId(), "Developer Flow")))
                .contains("Implementing changes Retrying after Starting build hit a network/download failure")
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
        assertThat(statusCard(body)).contains("Asked a question",
                "The agent stopped with a question Keep the old endpoint?");
        assertThat(text(body)).contains("2 · Implement changes Stopped with a question 3 · Verify changes Not started"
                + " 4 · Create pull request Not started");
        assertThat(body).contains("data-state=\"question\"").doesNotContain("NEEDS_DECISION", "NOT_RUN");
    }

    /** A Developer Flow run of the current version whose first PR feedback round was published. */
    private UUID seedPrFeedbackRun() {
        var execution = new PipelineExecution("dev-factory",
                org.folio.factory.devfactory.feedback.FeedbackArtifacts.VERSION, "{}");
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        execution = executions.save(execution);
        var id = execution.getId();
        var round = "\"round\":1,\"pr\":" + PR_6 + ",\"selected\":[" + DONE_COMMENT
                + "],\"roundStartedAt\":\"2026-11-10T18:22:00Z\"";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"CODE\"}", "application/json", "test");
        var checks = "{\"executionId\":\"" + id + "\",\"argv\":[],\"exitCode\":0,\"surefireReportCount\":1,"
                + "\"testCount\":300,\"failureCount\":0,\"errorCount\":0,\"result\":\"PASS\","
                + "\"output\":\"BUILD SUCCESS from the checks\",\"failsafeReportCount\":0}";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"READY\",\"commitSha\":\"" + "c".repeat(40)
                + "\",\"summary\":\"## Changes\\n- Added `minProperties` to the items object (PR feedback inline:1)."
                + "\\n\\n## Assumptions\\n- None.\",\"finishedAt\":\"2026-11-10T18:28:00Z\",\"runtimeName\":\"Pi\","
                + "\"verification\":" + checks + "}", "application/json", "test");
        // Round 2 stored Factory's own summary, which has no Changes section and so is no report of the agent.
        var second = "\"round\":2,\"pr\":" + PR_6 + ",\"selected\":[" + DONE_COMMENT
                + "],\"roundStartedAt\":\"2026-11-10T18:40:00Z\"";
        artifactStore.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"CODE\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"READY\",\"commitSha\":\"" + "d".repeat(40)
                + "\",\"summary\":\"Verified changes published to the same PR\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_comments.json", "{\"id\":\"s1\",\"pr\":" + PR_6 + ",\"comments\":["
                + DONE_COMMENT + "," + FRESH_COMMENT + "]}", "application/json", "test");
        artifactStore.put(id, "dev_delivery.json", "{\"state\":\"DELIVERED\",\"pullRequestUrl\":"
                + "\"https://github.com/o/mgr-applications/pull/6\",\"pullRequestDraft\":true}", "application/json", "test");
        reviews.save(new HitlReview(id, "pr-feedback", execution.getCurrentStepIndex(), "{}"));
        auditLog.record(id, AuditEventType.CONNECTOR_ACTION, "pr-follow-up", Map.of("action", "pr-round-comment",
                "round", 1, "commentUrl", "https://github.com/o/mgr-applications/pull/6#issuecomment-9"));
        return id;
    }

    private static final String PR_6 = "{\"repository\":\"o/mgr-applications\",\"number\":6,\"branch\":\"factory/x\","
            + "\"baseBranch\":\"master\",\"headSha\":\"" + "c".repeat(40)
            + "\",\"url\":\"https://github.com/o/mgr-applications/pull/6\",\"open\":true}";
    private static final String DONE_COMMENT = "{\"id\":\"inline:1\",\"author\":\"reviewer\",\"body\":\"Add minProperties\","
            + "\"path\":\"a.json\",\"line\":3,\"outdated\":false,\"updatedAt\":\"\","
            + "\"url\":\"https://github.com/o/mgr-applications/pull/6#discussion_r1\"}";
    private static final String FRESH_COMMENT = "{\"id\":\"inline:2\",\"author\":\"yauhen\",\"body\":\"Rename the test\","
            + "\"path\":\"AppTest.java\",\"line\":214,\"outdated\":false,\"updatedAt\":\"\","
            + "\"url\":\"https://github.com/o/mgr-applications/pull/6#discussion_r2\"}";

    @Test
    void prFeedbackWaitingForCommentsShowsTheNewOnesAndOneFilledButtonToFixThem() {
        var id = seedPrFeedbackRun();

        String body = assertRendered("/executions/" + id, "New PR comments");
        // While Factory waits for comments, the card names the pull request and nothing ticks.
        assertThat(statusCard(body)).contains("Draft PR #6", "Pull request #6 is open as a draft")
                .doesNotContain("Waiting for your review", "so far");
        assertThat(body.substring(body.indexOf("class=\"status-card\""), body.indexOf("class=\"run-steps\"")))
                .containsPattern(linkTo("https://github.com/o/mgr-applications/pull/6", "Draft PR #6"))
                .doesNotContain("data-elapsed-start");
        assertThat(text(body)).contains("Check PR comments", "Rename the test", "AppTest.java:214", "Fix 1 comment",
                "Starts a new round and adds to the cost.", "Your turn", "You decide");
        assertThat(body).contains("id=\"comment-0\"", "aria-labelledby=\"comment-0-text\"", "for=\"comment-0\"",
                        "id=\"comment-0-text\">Rename the test</span>", "data-selection=\"selectedIds\"",
                        // New comments wait for a choice, so the card and the list say it is the developer's turn.
                        "data-turn=\"YOURS\"")
                .containsPattern(linkTo("https://github.com/o/mgr-applications/pull/6#discussion_r2", "AppTest.java:214"))
                .doesNotContain(">Add minProperties</span>", "inline:1");
        // One filled button on the page: Fix N comments.
        assertThat(body.split("btn btn-primary", -1)).hasSize(2);
    }

    @Test
    void prFeedbackRoundsShowWhatEachRoundDidWithItsChecksCostAndFactorysComment() {
        var id = seedPrFeedbackRun();

        String body = assertRendered("/executions/" + id, "New PR comments");
        // One head line per round: the round and its commit, then the amount and the chip; the rest under it.
        // The start time is UTC text for time.js; the title has the whole line.
        assertThat(text(body)).contains("Round 1 1 comment ccccccc $0.00 Published", "6 min · 300 tests passed",
                "GitHub comment Add minProperties", "Coding agent", "Changes • Added minProperties to the items object."
                        + " Assumptions • None.", "Checks passed: 300 tests.", "Factory's comment on the PR",
                "Verify my manual changes",
                "Runs the checks on commits you pushed to the PR branch yourself. No agent, no cost.",
                "First implementation", "Round 2");
        assertThat(body)
                .contains("title=\"Nov 10, 18:22 UTC · 6 min · 300 tests passed\">"
                        + timeTag(PageTime.day(Instant.parse("2026-11-10T18:22:00Z"))))
                .containsPattern(linkTo("https://github.com/o/mgr-applications/pull/6/commits/" + "c".repeat(40),
                        "ccccccc"))
                .containsPattern(linkTo("https://github.com/o/mgr-applications/pull/6#issuecomment-9",
                        "Factory's comment on the PR"))
                .contains("<details class=\"fold\" id=\"fold-activity\">", "<details class=\"fold\" id=\"fold-usage\">")
                // The report is the agent's text without its Markdown, the PR feedback ids and the check output.
                .doesNotContain("## ", "inline:1", "BUILD SUCCESS from the checks", "Agent: ", "feedback-round-summary",
                        "<dt>Models</dt>", "<dt>Runtime</dt>", "<dt>Attempts</dt>")
                // Round 2 has Factory's own summary only, which is no report of the agent.
                .doesNotContain("Verified changes published to the same PR");
        assertThat(body).containsOnlyOnce("<div class=\"said said-agent\">");
    }

    @Test
    void prFeedbackTranscriptNamesTheImplementationAndEachRoundAsThePageDoes() {
        var id = seedPrFeedbackRun();
        transcriptRun(id, "implement", Instant.parse("2026-11-10T18:00:00Z"));
        transcriptRun(id, "pr-follow-up", Instant.parse("2026-11-10T18:23:00Z"));
        transcriptRun(id, "pr-follow-up", Instant.parse("2026-11-10T18:41:00Z"));

        String transcript = assertRendered("/executions/" + id + "/transcript", "Transcript");
        assertThat(text(transcript)).contains("Implementation", "Round 1 · PR feedback", "Round 2 · PR feedback")
                .doesNotContain("Run 2", "Run 3");
    }

    @Test
    void prFeedbackWithoutNewCommentsSaysHowToGetThem() {
        var id = seedPrFeedbackRun();
        artifactStore.put(id, "dev_pr_comments.json", "{\"id\":\"s2\",\"pr\":" + PR_6 + ",\"comments\":["
                + DONE_COMMENT + "]}", "application/json", "test");

        assertThat(text(assertRendered("/executions/" + id, "New PR comments")))
                .contains("No new comments. Add review comments on GitHub, then press Check PR comments.");
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
        var round = "\"round\":1,\"pr\":" + PR_6 + ",\"selected\":[],\"roundStartedAt\":\"2026-11-10T18:22:00Z\"";
        var checks = "{\"executionId\":\"" + id + "\",\"argv\":[],\"exitCode\":1,\"surefireReportCount\":1,"
                + "\"testCount\":300,\"failureCount\":2,\"errorCount\":0,\"result\":\"FAIL\","
                + "\"output\":\"AppTest.renames failed\",\"failsafeReportCount\":0}";
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"CODE\"}", "application/json", "test");
        artifactStore.put(id, "dev_pr_state.json", "{" + round + ",\"phase\":\"FAILURE\",\"verification\":" + checks
                + "}", "application/json", "test");

        String body = assertRendered("/executions/" + id, "Rounds");
        assertThat(text(body)).contains("Round 1", "300 tests, 2 failed", "Checks failed: 2 of 300 tests failed.",
                "Action needed", "AppTest.renames failed");
        assertThat(body).contains("title=\"Nov 10, 18:22 UTC · 300 tests, 2 failed\">"
                        + timeTag(PageTime.day(Instant.parse("2026-11-10T18:22:00Z"))))
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
        assertThat(statusCard(body)).contains("PR #42", "Pull request #42 is ready for review");
        assertThat(body).containsPattern(linkTo(url, "PR #42"))
                .doesNotContain("Open pull request", "<routing>", "DELIVERED");
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
        assertThat(text(list)).contains("The agent reached its limit of 40 turns before it finished.", providerError);
        assertThat(list)
                .containsPattern("title=\"MODBAR-781 · [^\"]+ · " + Pattern.quote(expected) + "\"")
                .containsPattern("title=\"MODBAR-782 · [^\"]+ · " + Pattern.quote(providerError + " Check the provider"
                        + " and the Developer Flow settings, then retry. Error: API Error: 429.") + "\"")
                // The age stays relative; time.js puts the local time into its title.
                .containsPattern("<time datetime=\"[\\dT:-]{19}Z\" data-local=\"ago\" title=\"[\\d-]{10} [\\d:]{8} UTC\">")
                .doesNotContain("Implementation failed", "shared: ", "DEVELOPMENT_FAILED");
        // The task page and the run page say it in the same words; a limit says where it is set, as a link under
        // the reason, not inside the sentence.
        String reasonCard = "Attempt 1 failed Reason " + expected + " Limits in Configuration"
                + " No pull request was created.";
        String task = assertRendered("/tasks/" + stopped.getId(), "Failed");
        assertThat(text(task)).contains(reasonCard);
        assertThat(task).containsPattern(linkTo("/developer/configuration#limits", "Limits in Configuration"))
                .doesNotContain("Implementation failed", "DEVELOPMENT_FAILED");
        // The attempt chip keeps the reason and what to do in its title; the meta line has its start time for
        // time.js, and no title in UTC, since the line itself shows the local time.
        assertThat(task.substring(task.indexOf("id=\"attempts-title\"")))
                .containsPattern(titled(expected, "Failed"))
                .containsPattern("<div(?![^>]*title=)[^>]*>" + Pattern.quote(
                        timeTag(PageTime.day(Instant.parse("2026-11-10T22:42:00Z")))) + "</div>");
        // The run page links back to its task and attempt.
        String run = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(text(run)).contains(reasonCard, "Attempt 1");
        assertThat(run).contains("<title>MODBAR-781 · attempt 1 — AI FOLIO</title>")
                .containsPattern(linkTo("/tasks/" + stopped.getId(), "MODBAR-781"))
                .containsPattern(linkTo("/developer/configuration#limits", "Limits in Configuration"))
                .doesNotContain("DEVELOPMENT_FAILED", "Implementation failed");
        // A failure that is not a limit has no such link.
        assertThat(assertRendered("/tasks/" + provider.getId(), "Failed")).doesNotContain("configuration#limits");
        String unchangedPage = assertRendered("/tasks/" + unchanged.getId(), "Failed");
        assertThat(text(unchangedPage)).contains("Reason The agent finished without changing any file."
                + " Start a new attempt with clearer instructions.");
        assertThat(unchangedPage).doesNotContain("Candidate contains no changes", "configuration#limits");
    }

    @Test
    void aCompletedDeveloperRunThatFailedSaysFailedOnItsPageAndInTheList() {
        var execution = completedDeveloperRun("DEVELOPMENT_FAILED");

        String detail = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(statusCard(detail)).contains("Failed", "This run failed");
        assertThat(detail).doesNotContain(">DEVELOPMENT_FAILED<", ">COMPLETED<", "chip-status-");
        // The list has the same words: a run without a pull request failed.
        assertThat(listRowOf(execution)).contains("Failed").doesNotContain("DEVELOPMENT_FAILED", "Completed");
    }

    @Test
    void aCompletedDeveloperRunThatDeliveredSaysFinishedOnItsPageAndInTheList() {
        var execution = completedDeveloperRun("DELIVERED");

        String detail = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        assertThat(statusCard(detail)).contains("Finished");
        assertThat(detail).doesNotContain(">DELIVERED<", ">COMPLETED<", "chip-status-");
        assertThat(listRowOf(execution)).contains("Finished").doesNotContain("DELIVERED", "Completed");
    }

    private PipelineExecution completedDeveloperRun(String outcome) {
        var execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setCurrentStepIndex(6);
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_result.json", "{\"state\":\"" + outcome + "\"}", "publish");
        return execution;
    }

    /** The visible text of the row of this run in the Developer Flow run list. */
    private String listRowOf(PipelineExecution execution) {
        String list = assertRendered("/executions?flow=dev-factory", "Executions");
        String row = list.substring(list.indexOf("href=\"/executions/" + execution.getId() + "\""));
        return text(row.substring(0, row.indexOf("</tr>")));
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
        assertThat(text(activity)).contains("Activity 3")
                .containsPattern("[\\d:]{8} UTC Running mvn test running now")
                .contains("Read /workspace/src/Foo.java took 6 ms",
                        "Ran cat &gt; Notes.java &lt;&lt;&#39;EOF&#39; failed · exit 2");
        assertThat(activity)
                .contains("href=\"/executions/" + execution.getId() + "/transcript\">Full transcript</a>",
                        "data-tone=\"live\"", "data-tone=\"failed\"", "data-local=\"clock\"",
                        "title=\"cat &gt; Notes.java &lt;&lt;&#39;EOF&#39;\"")
                .doesNotContain("Reading", "data-earlier");
        // The usage column and the technical details: closed, the checks that ran, the starting build output.
        assertThat(body).containsPattern("<details class=\"fold\" id=\"fold-technical\">\\s*<summary>"
                        + "<span class=\"fold-title\">Technical details</span>")
                .doesNotContain("<details class=\"disclosure\" open", "<details class=\"fold\" open")
                .contains("MODSIDECAR-196", "<code>mvn test</code> · exit code 0", "1 · Prepare task",
                        "2 · Implement changes", "3 · Verify changes", "4 · Create pull request",
                        "<summary>Starting build output</summary>", "Compiling 42 source files", "transfer failed",
                        "<span class=\"fold-title\">Artifacts</span><span class=\"fold-hint\">4</span>", "<dl class=\"tech-facts artifact-list\">", ">Audit log</a>", "No usage reported yet.");
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
        assertThat(body).contains("<span>glm-5.3-flash</span>", "Real provider charges may differ.")
                // The price source and its date, whatever the models file says today.
                .containsPattern("</a>\\s*\\(\\d{4}-\\d{2}-\\d{2}\\)")
                // A price source that is a web address opens in a new tab.
                .containsPattern("<a class=\"dev-link ext-link\" href=\"(https://[^\"]+)\" target=\"_blank\""
                        + " rel=\"noopener noreferrer\">\\1</a>")
                .containsPattern("<span class=\"status-card-money\">\\$0.02</span>\\s*"
                        + "<span class=\"status-card-note\">so far</span>")
                .doesNotContain("test-provider", "factory-dev-pi");
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
            assertThat(text(activity)).contains("Building the project running now")
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

    /** The visible text with each run of white space as one space; entities stay as the page has them. */
    private static String text(String html) {
        return visibleText(html).replaceAll("\\s+", " ");
    }

    /** The visible text of the run page's status card. */
    private static String statusCard(String body) {
        var start = body.indexOf("class=\"status-card\"");
        var end = body.indexOf("class=\"run-steps\"", start);
        return text(end < 0 ? body.substring(start) : body.substring(start, end));
    }

    /** A link to {@code url} whose text is {@code label}, whatever its other attributes. */
    private static String linkTo(String url, String label) {
        return "href=\"" + Pattern.quote(url) + "\"[^>]*>\\s*" + Pattern.quote(label) + "\\s*<";
    }

    /** A link with the {@code ext-link} class, in any attribute order. */
    private static String extLinkTo(String url, String label) {
        return "<a(?=[^>]*class=\"[^\"]*\\bext-link\\b)(?=[^>]*href=\"" + Pattern.quote(url) + "\")[^>]*>\\s*"
                + Pattern.quote(label) + "\\s*<";
    }

    /** A chip of this tone with this text. */
    private static String chip(String tone, String label) {
        return "<(?:a|span)(?=[^>]*class=\"chip chip-" + tone + "[\" ])[^>]*>\\s*" + Pattern.quote(label) + "\\s*<";
    }

    /** A form that posts to this action; the action is a regex. */
    private static String postForm(String actionPattern) {
        return "<form(?=[^>]*method=\"post\")(?=[^>]*action=\"" + actionPattern + "\")";
    }

    /** An element with this title and this text. */
    private static String titled(String title, String label) {
        return "title=\"" + Pattern.quote(title) + "\"[^>]*>\\s*" + Pattern.quote(label) + "\\s*<";
    }

    /** The start of a radio button with this name and value, whatever the order of its attributes. */
    private static String radio(String name, String value) {
        return "<input(?=[^>]*type=\"radio\")(?=[^>]*name=\"" + Pattern.quote(name) + "\")(?=[^>]*value=\""
                + Pattern.quote(value) + "\")";
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
                .doesNotContain("PROVIDER_RETRIES_EXHAUSTED");
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
                // Nothing to read yet: Full transcript waits for the first activity row.
                .doesNotContain("Full transcript");
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
    void nonDeveloperCompletedStatusIsUnchangedEvenWithDeveloperNamedArtifact() {
        var execution = new PipelineExecution("test-factory", "1", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution = executions.save(execution);
        artifactStore.putMarkdown(execution.getId(), "dev_result.json",
                "{\"state\":\"DEVELOPMENT_FAILED\"}", "test");
        assertThat(assertRendered("/executions/" + execution.getId(), "Execution"))
                .contains("class=\"chip chip-status-COMPLETED\">COMPLETED</span>")
                .doesNotContain("chip-status-DEVELOPMENT_FAILED");
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
        resetTaskData();
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        var artifact = artifactStore.putMarkdown(execution.getId(), "coverage_report.md",
                "# Coverage\nAll branches hit.\n", "test-exec");
        var developerRun = executions.save(
                new PipelineExecution("dev-factory", "0.9.0", "{\"issueKey\":\"MGRAPPS-119\"}"));
        var attempt = seedAttempt(seedTask("70119", "MGRAPPS-119", "Artifact task", Task.Eligibility.ELIGIBLE,
                "repository mgr-applications"), 3, TaskAttempt.Kind.RETRY, TaskAttempt.State.RUNNING, null);
        attempt.setExecutionId(developerRun.getId());
        taskAttempts.save(attempt);
        artifactStore.putMarkdown(developerRun.getId(), "dev_task_brief.md", "# Brief\n", "read-task");

        var created = timeTag(PageTime.timestamp(artifact.getCreatedAt()));
        String list = assertRendered("/artifacts", "Immutable artifact versions across all runs");
        // The list has the short time; the artifact page keeps the seconds. Each flow is named as on Executions,
        // and a Developer Flow run by its task and attempt.
        assertThat(list).contains("coverage_report.md", "<span>Test Factory</span>", "<span>Developer Flow</span>",
                        "<th>Run</th>", "href=\"/executions/" + developerRun.getId() + "\">MGRAPPS-119 · attempt 3</a>",
                        "href=\"/executions/" + execution.getId() + "\">" + execution.getId().toString().substring(0, 10))
                .contains("<td class=\"muted\">" + timeTag(PageTime.day(artifact.getCreatedAt())) + "</td>")
                .doesNotContain("<span>test-factory</span>", "<span>dev-factory</span>", "<th>Execution</th>");

        String detail = assertRendered("/artifacts/" + artifact.getId(), "All branches hit.");
        assertThat(detail).contains("coverage_report.md", "<span>Test Factory</span>")
                .contains("test-exec · " + created + " · text/markdown");
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

    /** The tasks My tasks shows in every part of its page, seeded together. */
    private record MyTasksSeed(Task awaiting, Task unsupported, Task stopped, Task held, Task added,
                               PipelineExecution liveExecution) {
    }

    private MyTasksSeed seedMyTasks() {
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
        seedPullRequest(seedTask("70006", "MODBAR-706", "Delivered task", Task.Eligibility.ELIGIBLE,
                "repository mod-bar"), "https://github.com/folio-org/mod-bar/pull/7", null);
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
        seedPullRequest(seedTask("70010", "MODBAR-710", "Draft task", Task.Eligibility.ELIGIBLE,
                "repository mod-bar"), "https://github.com/folio-org/mod-bar/pull/8",
                "[\"1 acceptance criterion is not met.\"]");
        Task added = seedTask("70013", "MODBAR-713", "Added and not run yet",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        added.setAddedByKey(true);
        taskTasks.save(added);
        return new MyTasksSeed(taskByKey("MODBAR-701"), taskByKey("MODBAR-702"), stopped, held, added,
                liveExecution);
    }

    private void seedPullRequest(Task task, String url, String draftReasons) {
        TaskAttempt attempt = seedAttempt(task, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.PR_READY, null);
        attempt.setPublicationIdentity(url);
        attempt.setDraftReasons(draftReasons);
        taskAttempts.save(attempt);
    }

    private Task taskByKey(String key) {
        return taskTasks.findAll().stream().filter(task -> task.getDisplayKey().equals(key)).findFirst()
                .orElseThrow();
    }

    @Test
    void myTasksHeaderHasItsTabsAndTheStripOfTheRunningTask() {
        var seed = seedMyTasks();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        // Tabs in the header row; the configuration is a tab, not a button.
        assertThat(body)
                .contains("aria-current=\"page\">My tasks</a>", "href=\"/flows/dev-factory?view=definition\"")
                .containsPattern(linkTo("/developer/configuration", "Configuration"))
                .doesNotContain("Check configuration", "assignee = &quot;Jane Developer&quot;");
        // The running task: one strip with its state first, then the task, then the shortcut to its run.
        assertThat(text(body)).contains("In progress MODBAR-709 Live task");
        assertThat(body)
                .containsPattern(extLinkTo("https://jira.example.org/browse/MODBAR-709", "MODBAR-709"))
                .containsPattern(linkTo("/executions/" + seed.liveExecution().getId(), "Open run"));
        assertThat(rest.get().uri("/tasks").retrieve().toEntity(String.class).getBody()).contains("Ready to run");
    }

    @Test
    void myTasksReadyToRunListsTheTasksThatCanRunAndTheWaysToAddMore() {
        var seed = seedMyTasks();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        String readyList = body.substring(body.indexOf("id=\"available-title\""), body.indexOf("id=\"triggered-title\""));
        assertThat(text(readyList)).contains("Ready to run 3", "MODBAR-701 Status in Jira: Open",
                "MODBAR-703 Status in Jira: In Review", "mod-bar");
        assertThat(readyList)
                // A task that has not started has no page worth opening: its name selects it instead.
                .containsPattern("<label [^>]*for=\"select-[0-9a-f-]+\"")
                .doesNotContain("href=\"/tasks/")
                .contains("action=\"/flows/dev-factory/add-by-key\"", "placeholder=\"Add by key, for example MGRAPPS-101\"",
                        ">Add</button>", "action=\"/flows/dev-factory/scan\"", ">Scan Jira</button>",
                        "title=\"Not scanned yet\"", "action=\"/tasks/run-selected\"",
                        "aria-label=\"Select MODBAR-701\"", "title=\"MODBAR-701 · Status in Jira: Open · mod-bar\"")
                .containsPattern(linkTo("https://jira.example.org/browse/MODBAR-701", "MODBAR-701"))
                .contains("data-run-selected disabled>Run</button>")
                .doesNotContain("Select MODBAR-702", "added by key");
        // The Jira status follows the key, so it never reads as "open in Jira"; a screen reader hears what it is.
        // The effort levels need no label.
        assertThat(readyList).contains("<span class=\"sr-only\">Status in Jira: </span>")
                .doesNotContain("<span>Jira: ", ">Effort</span>");
        // Tasks that cannot run: one line per reason under the list, no disclosure.
        assertThat(text(readyList)).contains("1 task cannot run here: MODBAR-702. Project MODBAR has no"
                + " repository in config/developer-repositories.yaml.");
        assertThat(readyList).doesNotContain("cannot run here</summary>");
        // The effort choice says what it means on hover. Only a task added by key has Remove from list, named
        // with its visible text.
        assertThat(readyList)
                .contains("title=\"Effort is how long the model thinks at each step: higher is slower and costs more.\"")
                .containsPattern("<button(?=[^>]*formaction=\"/tasks/" + seed.added().getId() + "/remove-from-list\")"
                        + "(?=[^>]*aria-label=\"Remove from list: MODBAR-713\")[^>]*>\\s*Remove from list\\s*</button>")
                .containsOnlyOnce("Remove from list</button>")
                .doesNotContain(">Remove</button>");
    }

    @Test
    void myTasksRunFormOffersTheReadyModelsAndEachRowTheEffortOfTheSelectedModel() {
        var seed = seedMyTasks();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        // Run: one Model for the run, the ready models only, each with its levels for the page script.
        String run = body.substring(body.indexOf("action=\"/tasks/run-selected\""), body.indexOf("data-run-selected"));
        assertThat(text(run)).contains("Model");
        assertThat(run).contains("name=\"modelId\"", "data-model-select")
                .containsPattern("<option value=\"codemie-sonnet-5.5\"(?=[^>]*selected=\"selected\")"
                        + "(?=[^>]*data-efforts=\"low medium high xhigh max\")(?=[^>]*data-default-effort=\"high\")")
                .containsPattern("<option value=\"glm-5.3\"(?=[^>]*data-efforts=\"low high max\")"
                        + "(?=[^>]*data-default-effort=\"max\")")
                .doesNotContain("value=\"anthropic-sonnet-5.5\"", "value=\"codemie-gemini-3.8-flash\"");
        // Effort on each row that can run: radio buttons of the selected model, its default checked.
        assertThat(body)
                .containsPattern("role=\"radiogroup\"[^>]*aria-label=\"Effort for MODBAR-701\"")
                .containsPattern(radio("effort-" + seed.awaiting().getId(), "high") + "(?=[^>]*checked=\"checked\")")
                .containsPattern(radio("effort-" + seed.awaiting().getId(), "xhigh") + "(?![^>]*checked)")
                .doesNotContain("name=\"effort-" + seed.unsupported().getId() + "\"", "Max turns", "name=\"maxTurns\"");
        assertThat(body).doesNotContain(ZAI_KEY, PROXY_TOKEN, "not configured)");
    }

    @Test
    void myTasksRunsShowEachTaskWithItsChipAndLinkAndOnlyTheHeldTaskHasAButton() {
        var seed = seedMyTasks();

        String body = assertRendered("/flows/dev-factory", "Ready to run");
        String triggered = body.substring(body.indexOf("id=\"triggered-title\""));
        assertThat(text(triggered)).contains("Queued", "next in line", "2nd in line",
                "Checks failed: 2 tests in error", "Failed", "Needs inspection", "In progress",
                "outcome not confirmed", "Queue is waiting.", "MODBAR-707 needs inspection",
                "Queued tasks are not starting.", "FACTORY_TASKS_EXECUTION_ENABLED=true");
        assertThat(triggered)
                .containsPattern(linkTo("https://github.com/folio-org/mod-bar/pull/7", "PR #7"))
                .containsPattern(linkTo("https://github.com/folio-org/mod-bar/pull/8", "Draft PR #8"))
                // The pull request chip opens GitHub and looks clickable; each chip has the tone of its state.
                .containsPattern(extLinkTo("https://github.com/folio-org/mod-bar/pull/7", "PR #7"))
                .containsPattern(chip("success", "PR #7"))
                .containsPattern(chip("danger", "Failed"))
                .containsPattern(chip("info", "In progress"))
                .containsPattern(linkTo("/tasks/" + seed.stopped().getId(), "Stopped task"))
                // A row is one link to its task and has no button of its own: a task that waits for the developer
                // says Your turn, and its task page starts with the answer or the next attempt.
                .doesNotContain("aria-label=\"Retry MODBAR-705\"", "aria-label=\"Retry MODBAR-714\"",
                        "aria-label=\"Open run MODBAR-705\"", "aria-label=\"Open run MODBAR-709\"", ">Retry</button>",
                        "Triggered", "Working", "Withdrawn");
        // A held task offers Mark as stopped, which asks first and returns to My tasks.
        assertThat(triggered)
                .containsPattern(postForm("/tasks/" + seed.held().getId() + "/attempts/[0-9a-f-]+/mark-stopped"))
                .contains("<input type=\"hidden\" name=\"returnTo\" value=\"flow\">",
                        "aria-label=\"Mark as stopped: MODBAR-707\"", "data-confirm-title=\"Mark MODBAR-707 as stopped?\"",
                        "The task becomes Failed, its cost so far stays");
        // The page reloads itself when the poll says something changed.
        assertThat(body).contains("data-selection=\"taskId\"", "data-run-selected", "data-poll-notice",
                "data-poll-url=\"/tasks/poll\"", "data-poll-fields=\"revision\"", "data-poll-baseline=")
                .doesNotContain("Trigger flow");
    }

    @Test
    void myTasksShowsARunThatAskedAsNeedsYourAnswerAndOpensTheTaskPage() {
        resetTaskData();
        Task asked = seedTask("70020", "MODBAR-720", "Asking task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(asked, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API should change?");

        String body = assertRendered("/flows/dev-factory", "Started tasks");

        String runs = body.substring(body.indexOf("id=\"triggered-title\""));
        // The row says it waits for the developer and opens the task, whose page starts with the question.
        assertThat(text(runs)).contains("Needs your answer", "Your turn");
        assertThat(runs).containsPattern(linkTo("/tasks/" + asked.getId(), "Asking task"))
                .containsPattern(chip("warning", "Needs your answer"))
                .doesNotContain("Which API should change?", ">Retry</a>", ">Answer</a>");
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
        assertThat(body).containsPattern("<section class=\"dev-section[^\"]*\" id=\"retry\"")
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
    void developerFlowDefinitionListsItsStepsAndHasNoTrigger() {
        String body = assertRendered("/flows/dev-factory?view=definition", "Steps <span class=\"dev-count\">10</span>");
        assertThat(body).contains("aria-current=\"page\">Definition</a>", ">Configuration</a>",
                        ">1 · Read task</div>", ">10 · PR follow up</div>",
                        "dev_decision_request.md → no artifacts", "no artifacts → dev_pr_state.json, dev_pr_request.json",
                        "Worker dev-pr-follow-up, can pause and go on where it stopped", ">Needs issueKey</div>",
                        "Step retries: up to 2, waiting 30 s, then 2 min.")
                .doesNotContain("trigger-dialog", "Trigger flow", "class=\"stepper\"", "30120300", "Agent step");
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

    /** A task whose first attempt failed verification, with its run, its settings and $0.02 of usage. */
    private record FailedTask(Task task, PipelineExecution execution) {
    }

    private FailedTask seedFailedTask() {
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
        return new FailedTask(stopped, execution);
    }

    @Test
    void aFailedTaskPageSaysWhyItFailedWhatItCostAndThatItIsYourTurn() {
        var failed = seedFailedTask();

        String body = assertRendered("/tasks/" + failed.task().getId(), "Failed");
        assertThat(text(body)).contains("Attempt 1 failed Reason Checks failed: 2 tests in error. No pull request was created.", "$0.02 spent in 1 attempt",
                // The head says what comes from Jira and what Factory adds; a failed attempt is the developer's turn.
                "Jira task MODBAR-721", "Factory mod-bar", "Your turn");
        assertThat(body)
                .containsPattern(linkTo("/flows/dev-factory", "Developer Flow"))
                .containsPattern(linkTo("/executions/" + failed.execution().getId(), "Open run"))
                .contains("data-turn=\"YOURS\"")
                // Technical details are gone: the URL, the header and the status card say all they held.
                .doesNotContain("Technical details", "Total tokens");
    }

    @Test
    void aFailedTaskPageStartsTheNextAttemptFromTheConfiguredModelAndAnEmptyTextField() {
        var failed = seedFailedTask();

        String body = assertRendered("/tasks/" + failed.task().getId(), "Failed");
        assertThat(text(body)).contains("Start attempt 2", "Effort",
                "A new attempt starts from a clean checkout and adds to the cost. Effort is how long the model"
                        + " thinks at each step: higher is slower and costs more.");
        // The next attempt is its own section; its text field is empty and has a name for screen readers.
        assertThat(body)
                .contains("id=\"retry\"", "name=\"expectedVersion\"", "name=\"inputFingerprint\"",
                        "name=\"destinationFingerprint\"",
                        "<label for=\"retry-instructions\">What should the agent do differently? (optional)</label>",
                        "id=\"retry-instructions\" name=\"instructions\"", "maxlength=\"8000\"",
                        "placeholder=\"For example: change only FolioTenantApiClient and its test.\"></textarea>")
                .containsPattern(postForm("/tasks/" + failed.task().getId() + "/retry"))
                .doesNotContain(">Read the ADR through the wiki &lt;REST&gt; URL.</textarea>", "value=\"77\"", "Max turns")
                // A new attempt starts from the configured model and effort, not from the previous attempt's.
                .containsPattern("<option value=\"codemie-sonnet-5.5\"[^>]*selected=\"selected\"")
                .doesNotContainPattern("<option value=\"glm-5.3\"[^>]*selected=\"selected\"")
                .containsPattern(radio("effort", "high") + "(?=[^>]*checked=\"checked\")");
        assertThat(body).doesNotContain(ZAI_KEY);
    }

    @Test
    void aFailedTaskPageListsItsAttemptWithItsSettingsAndInstructions() {
        var failed = seedFailedTask();

        String body = assertRendered("/tasks/" + failed.task().getId(), "Failed");
        String attempts = body.substring(body.indexOf("id=\"attempts-title\""));
        assertThat(text(attempts)).contains("Attempt 1", "first run",
                "glm-5.3 · low", "&quot;Read the ADR through the wiki &lt;REST&gt; URL.&quot;");
        // Only the quote may end with an ellipsis on a narrow row: it is an element of its own.
        assertThat(attempts).contains(">&quot;Read the ADR through the wiki &lt;REST&gt; URL.&quot;</span>")
                .containsPattern("<a(?=[^>]*href=\"/executions/" + failed.execution().getId() + "\")"
                        + "(?=[^>]*aria-label=\"Open run for attempt 1\")");
    }

    @Test
    void aPartlyReportedCostIsUnderOneCentAndSaysWhyInItsTitle() {
        resetTaskData();
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
        // 50,000 tokens at $0.15 per million cost $0.0075: under one cent, never "$0.00" or "$0.01".
        assertThat(partialBody).doesNotContain("spent in 1 attempt, cost may be higher")
                .containsPattern(titled("Incomplete: some usage has no reported tokens or rate yet. Cost per attempt"
                        + " is in Attempts.", "&lt;$0.01"));
        String costTitle = "Some usage was not reported by the runtime or has no price, so the real cost may be higher.";
        assertThat(partialBody.substring(partialBody.indexOf("id=\"attempts-title\"")))
                .containsPattern(titled(costTitle, "&lt;$0.01")).doesNotContain(">incomplete<");
        String partialList = assertRendered("/flows/dev-factory", "Ready to run");
        assertThat(partialList.substring(partialList.indexOf("id=\"triggered-title\"")))
                .containsPattern(titled(costTitle, "&lt;$0.01"))
                .doesNotContain(">incomplete<", ">cost may be higher<");
    }

    @Test
    void aLiveAttemptShowsItsTimeAgainstItsLimitAndItsCostSoFar() {
        resetTaskData();
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
        assertThat(text(liveBody)).contains("In progress", "Open run");
        // Why the amount can still change is its title.
        assertThat(liveBody).containsPattern("title=\"[^\"]*Includes live usage that can still change.\"").doesNotContain("name=\"expectedVersion\"", "Implementing changes.");
        // The status card shows the time of the coding step against its own limit, as the run page does.
        String side = liveBody.substring(liveBody.indexOf("class=\"status-card-side\""));
        assertThat(side).contains("title=\"The whole attempt may take 2 h.\"").containsPattern("data-elapsed-start=\"[^\"]+\"");
        assertThat(text(side)).containsPattern("\\d+ s of 1 h");
        // A running attempt's cost is "so far", the same as Working in Runs.
        String attempts = liveBody.substring(liveBody.indexOf("id=\"attempts-title\""));
        assertThat(text(attempts)).contains("&lt;$0.01 so far");
        assertThat(attempts).containsPattern(titled("Implementing changes", "In progress"))
                .doesNotContain("cost may be higher", "cost unknown");
    }

    @Test
    void aStartThatReportedNoUsageSaysCostUnknownOnTheTaskPageAndNothingInRuns() {
        // A paid start that stopped before the runtime reported any usage, for example on a Docker error:
        // there is no cost figure, so the note says the cost is unknown, not that it may be higher.
        resetTaskData();
        Task unreported = seedTask("70024", "MODBAR-724", "Unreported task",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        PipelineExecution unreportedExecution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        TaskAttempt unreportedAttempt = seedAttempt(unreported, 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: the coding container did not start");
        unreportedAttempt.setExecutionId(unreportedExecution.getId());
        unreportedAttempt.setPaidStartAt(Instant.now());
        taskAttempts.save(unreportedAttempt);

        String unreportedBody = assertRendered("/tasks/" + unreported.getId(), "Failed");
        assertThat(unreportedBody.substring(unreportedBody.indexOf("id=\"attempts-title\"")))
                .containsPattern(titled("The runtime did not report usage, or the usage has no price, so the cost is"
                        + " not known.", "cost unknown"))
                .doesNotContain("cost may be higher");
        String unreportedList = assertRendered("/flows/dev-factory", "Ready to run");
        String unreportedRow = unreportedList.substring(unreportedList.indexOf(">MODBAR-724</a>"));
        assertThat(unreportedRow.substring(0, unreportedRow.indexOf("class=\"chip")))
                .doesNotContain("cost may be higher", "cost unknown", "—");
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

        String list = assertRendered("/flows/dev-factory", "Started tasks");
        assertThat(list.substring(list.indexOf(">MODBAR-733</a>")))
                .containsPattern("<span class=\"row-amount\"[^>]*>\\$0.30</span>");
        assertThat(assertRendered("/tasks/" + delivered.getId(), "Pull request #9"))
                .contains("<span class=\"status-card-money\">$0.30</span>");
    }

    @Test
    void theDashboardSaysWhatTheDeveloperFlowSpentAndHowMuchEndedInAPullRequest() {
        resetTaskData();
        var delivered = seedTask("7901", "MODBAR-791", "Task delivered on its second attempt",
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
        spendingAttempt(delivered, 1, TaskAttempt.State.STOPPED, 400_000);
        var pullRequest = spendingAttempt(delivered, 2, TaskAttempt.State.PR_READY, 300_000);
        pullRequest.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/41");
        taskAttempts.save(pullRequest);
        spendingAttempt(seedTask("7902", "MODBAR-792", "Task that only failed", Task.Eligibility.ELIGIBLE,
                "repository mod-bar"), 1, TaskAttempt.State.STOPPED, 1_200_000);
        spendingAttempt(seedTask("7903", "MODBAR-793", "Task whose usage has no price", Task.Eligibility.ELIGIBLE,
                "repository mod-bar"), 1, TaskAttempt.State.STOPPED, null);
        startedDaysAgo(spendingAttempt(seedTask("7904", "MODBAR-794", "Task that ran three weeks ago",
                Task.Eligibility.ELIGIBLE, "repository mod-bar"), 1, TaskAttempt.State.STOPPED, 5_000_000), 21);

        String block = spendingBlock(assertRendered("/", "Developer Flow spending"));
        assertThat(text(block)).contains("$1.90 Spent", "$0.30 Ended in a pull request",
                "$1.60 Attempts without a pull request", "$1.90 Cost per pull request", "1 attempt has no price")
                // Most expensive first; each row has its Jira key, its attempts, its amount and its My tasks chip.
                .containsSubsequence("Task that only failed MODBAR-792 1 attempt $1.20 Failed",
                        "Task delivered on its second attempt MODBAR-791 2 attempts $0.70 PR #41",
                        "Task whose usage has no price MODBAR-793 1 attempt cost unknown Failed")
                .doesNotContain("MODBAR-794");
        assertThat(block).containsPattern(linkTo("/tasks/" + delivered.getId(), "Task delivered on its second attempt"))
                .contains("aria-label=\"Ended in a pull request: $0.30 · No pull request: $0.40\"")
                .doesNotContain("class=\"kpi\"");
        // The row says what the task page says.
        assertThat(text(assertRendered("/tasks/" + delivered.getId(), "Pull request #41")))
                .contains("$0.70 spent in 2 attempts");
        // A longer period counts the older run too.
        assertThat(text(spendingBlock(assertRendered("/?days=30", "Developer Flow spending"))))
                .contains("$6.90 Spent", "$0.30 Ended in a pull request", "$6.60 Attempts without a pull request",
                        "$6.90 Cost per pull request")
                .containsSubsequence("MODBAR-794 1 attempt $5.00 Failed", "MODBAR-792");
    }

    @Test
    void theDashboardShowsNoDeveloperFlowSpendingWhenNoAttemptStartedInThePeriod() {
        resetTaskData();
        assertThat(assertRendered("/", "Dashboard")).doesNotContain("Developer Flow spending");
        startedDaysAgo(spendingAttempt(seedTask("7905", "MODBAR-795", "Task that ran three weeks ago",
                Task.Eligibility.ELIGIBLE, "repository mod-bar"), 1, TaskAttempt.State.STOPPED, 100_000), 21);

        assertThat(assertRendered("/", "Dashboard")).doesNotContain("Developer Flow spending");
        assertThat(text(spendingBlock(assertRendered("/?days=30", "Developer Flow spending"))))
                .contains("$0.10 Spent", "$0.00 Ended in a pull request", "— Cost per pull request")
                .doesNotContain("no price");
    }

    @Test
    void costPerTaskShowsTheFifteenMostExpensiveTasksAndOneLineForTheRest() {
        resetTaskData();
        for (int cents = 1; cents <= 17; cents++) {
            var number = String.format("75%02d", cents);
            spendingAttempt(seedTask(number, "MODBAR-" + number, "Task of " + cents + " cents",
                    Task.Eligibility.ELIGIBLE, "repository mod-bar"), 1, TaskAttempt.State.STOPPED, cents * 10_000L);
        }

        String block = text(spendingBlock(assertRendered("/", "Developer Flow spending")));
        assertThat(block).contains("$1.53 Spent", "2 more tasks: $0.03")
                .containsSubsequence("MODBAR-7517", "MODBAR-7516", "MODBAR-7503", "2 more tasks")
                .doesNotContain("MODBAR-7502", "MODBAR-7501");
    }

    /** The Developer Flow spending block of a Dashboard page, which stands between the tiles and the charts. */
    private static String spendingBlock(String dashboard) {
        int start = dashboard.indexOf("id=\"spending-title\"");
        assertThat(start).as("spending block").isPositive()
                .isGreaterThan(dashboard.indexOf("Cancelled or rejected</span>"));
        return dashboard.substring(start, dashboard.indexOf("class=\"charts-grid\""));
    }

    /**
     * An attempt whose run started now and reported {@code inputTokens} at $1 per million, all four token kinds
     * given; null tokens report 100,000 tokens without a price.
     */
    private TaskAttempt spendingAttempt(Task task, int ordinal, TaskAttempt.State state, Long inputTokens) {
        var execution = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        var attempt = seedAttempt(task, ordinal, ordinal == 1 ? TaskAttempt.Kind.INITIAL : TaskAttempt.Kind.RETRY,
                state, state == TaskAttempt.State.STOPPED ? "DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON : null);
        attempt.setExecutionId(execution.getId());
        attempt.setStartedAt(Instant.now());
        var rate = inputTokens == null ? Map.of() : Map.of("currency", "USD", "inputPerMillion", 1,
                "cacheReadPerMillion", 0, "cacheWritePerMillion", 0, "outputPerMillion", 0);
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "implement", "smoke",
                Map.of("attemptId", "a" + ordinal, "round", -1, "model", "test-model",
                        "inputTokens", inputTokens == null ? 100_000 : inputTokens, "cacheReadTokens", 0,
                        "cacheWriteTokens", 0, "outputTokens", 0, "rate", rate));
        return taskAttempts.save(attempt);
    }

    private TaskAttempt spendingAttempt(Task task, int ordinal, TaskAttempt.State state, long inputTokens) {
        return spendingAttempt(task, ordinal, state, Long.valueOf(inputTokens));
    }

    /** Moves the start of the attempt's run back by that many days; a run's start is never updated otherwise. */
    private void startedDaysAgo(TaskAttempt attempt, int days) {
        jdbc.sql("UPDATE pipeline_execution SET created_at = :at WHERE id = :id")
                .param("at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(days))
                .param("id", attempt.getExecutionId())
                .update();
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
        assertThat(text(body)).contains("Needs your answer", "The agent stopped with a question",
                "Remove &lt;old&gt; routes too?", "removeRoutes runs only on a new event.",
                "Anything to add for the agent? (optional)", "Start attempt 2 with this answer",
                "A new attempt starts from a clean checkout and adds to the cost.");
        assertThat(body)
                .doesNotContain("Remove <old> routes too?")
                .contains("role=\"radiogroup\" aria-label=\"Your answer\"",
                        "<span id=\"answer-0-text\">Only from now on.</span>",
                        "<span id=\"answer-1-text\">Also the old ones.</span>",
                        "<label for=\"answer-own\">", "name=\"note\"")
                .containsPattern("<span\\s+id=\"answer-own-text\">My own answer</span>")
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

        String body = assertRendered("/tasks/" + delivered.getId(), "Pull request #7 is ready for review");
        // The chip opens the pull request; the main button opens it too, the other one the PR feedback of the run.
        String pr = "https://github.com/folio-org/mod-bar/pull/7";
        assertThat(body)
                .containsPattern(linkTo(pr, "PR #7"))
                .containsPattern(linkTo(pr, "Open pull request #7"))
                .containsPattern(linkTo("/executions/" + execution.getId() + "#pr-feedback", "Open run"))
                .containsPattern("<a(?=[^>]*btn-primary)(?=[^>]*href=\"" + Pattern.quote(pr) + "\")")
                .doesNotContain("Draft PR", "Factory opened it as a draft because:")
                .doesNotContain("name=\"expectedVersion\"");
        assertThat(body.split("btn btn-primary", -1)).hasSize(2);
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
        String pr = "https://github.com/folio-org/mod-bar/pull/8";

        String body = assertRendered("/tasks/" + delivered.getId(), "Pull request #8 is open as a draft");
        assertThat(text(body)).contains("Draft PR #8", "Pull request #8 is open as a draft",
                "Factory opened it as a draft because: 1 acceptance criterion is not met."
                        + " The agent made assumptions that a reviewer should confirm.");
        assertThat(body).containsPattern(linkTo(pr, "Draft PR #8")).containsPattern(linkTo(pr, "Open pull request #8"))
                .doesNotContain("chip-success", "is ready for review");
        // The Attempts row marks the draft with the chip of the status card.
        assertThat(body.substring(body.indexOf("id=\"attempts-title\"")))
                .containsPattern("<a(?=[^>]*href=\"" + Pattern.quote(pr) + "\")"
                        + "(?=[^>]*title=\"Pull request #8 opened as a draft\")[^>]*>Draft PR #8</a>")
                .contains("aria-label=\"Open run for attempt 1\"");

        String run = assertRendered("/executions/" + execution.getId(), "Developer Flow");
        // The run page lists why the pull request is a draft, and its meta line names the repository as the task
        // page does.
        assertThat(text(run)).contains("Draft PR #8", "Pull request #8 is open as a draft",
                "Factory opened it as a draft because: 1 acceptance criterion is not met.", "mod-bar",
                "4 · Create pull request Passed · draft");
        assertThat(run).containsPattern(linkTo(pr, "Draft PR #8"))
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
        Task failed = seedTask("70062", "MODBAR-762", "Failed task", Task.Eligibility.ELIGIBLE, "repository mod-bar");
        seedAttempt(failed, 1, TaskAttempt.Kind.INITIAL, TaskAttempt.State.STOPPED,
                "VERIFICATION_FAILED: 2 tests in error");

        String body = assertRendered("/tasks/" + held.getId(), "Needs inspection");
        assertThat(text(body)).contains("Factory could not confirm how the run ended", "Mark as stopped…");
        assertThat(body).contains("/attempts/" + heldAttempt.getId() + "/mark-stopped", "data-confirm-style=\"compact\"")
                .doesNotContain("name=\"expectedVersion\"");
        assertThat(assertRendered("/tasks/" + failed.getId(), "Failed")).doesNotContain("/mark-stopped",
                "Mark as stopped");
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
        assertThat(text(body)).contains("Attempt 3", "retry", "first run", "Verification failed: second failure",
                "Failed");
        // The chip of an older attempt says how it ended in its title.
        assertThat(body).containsPattern(titled("Withdrawn before it started", "Withdrawn"))
                .containsPattern(titled("Verification failed: first failure.", "Failed"));
        String attemptsTable = body.substring(body.indexOf("id=\"attempts-title\""));
        // The whole attempt row opens its run, as a row of Runs opens its task.
        assertThat(attemptsTable)
                .containsPattern("<a(?=[^>]*href=\"/executions/" + first.getId() + "\")"
                        + "(?=[^>]*aria-label=\"Open run for attempt 2\")")
                .containsPattern("<a(?=[^>]*href=\"/executions/" + second.getId() + "\")"
                        + "(?=[^>]*aria-label=\"Open run for attempt 3\")")
                .doesNotContain(">Open run</a>")
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
        assertThat(text(header(body))).contains("Running task", "Attempt 1").doesNotContain("In progress");
        assertThat(header(body))
                .containsPattern(linkTo("/flows/dev-factory", "Developer Flow"))
                .containsPattern(linkTo("/tasks/" + task.getId(), "MODBAR-771"))
                .containsPattern(linkTo("https://jira.example.org/browse/MODBAR-771", "MODBAR-771"));
        assertThat(body)
                .contains("<title>MODBAR-771 · attempt 1 — AI FOLIO</title>",
                        "data-poll-url=\"/executions/" + execution.getId() + "/poll\"",
                        "<p class=\"dev-poll-notice\" role=\"status\" data-poll-notice hidden>")
                .containsPattern("href=\"/flows\"[^>]*aria-current=\"page\"")
                .doesNotContain("chip-status-RUNNING");
        // Right after the start: its own headline, the amount from the first render, the time alone.
        String card = text(body.substring(body.indexOf("class=\"status-card\"")));
        assertThat(card).contains("In progress", "Starting the run", "$0.00 so far");
        assertThat(elapsedStart(body)).isNotBlank();
        // Before the coding step the time has no limit beside it.
        assertThat(text(body.substring(body.indexOf("class=\"status-card-side\"")))).doesNotContain("of 1 h");
        // While the coding step works, the time is that step's, against the step's own limit.
        auditLog.record(execution.getId(), AuditEventType.STEP_STARTED, "implement", "smoke", Map.of());
        String coding = assertRendered("/executions/" + execution.getId(), "Implementing changes</p>");
        String codingSide = coding.substring(coding.indexOf("class=\"status-card-side\""));
        assertThat(codingSide).contains("title=\"The whole attempt may take 2 h.\"");
        assertThat(text(codingSide)).containsPattern("\\d+ s of 1 h");
        // The coding agent started and has reported no usage yet: the run has cost nothing so far, and the
        // time still counts from the step's start, so it never goes back.
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement", "smoke",
                Map.of("activity", "coding_starting", "attemptId", "attempt-1"));
        String agent = assertRendered("/executions/" + execution.getId(), "Implementing changes</p>");
        assertThat(text(agent.substring(agent.indexOf("class=\"status-card-side\"")))).contains("$0.00 so far");
        assertThat(elapsedStart(agent)).isEqualTo(elapsedStart(coding));
    }

    /** Where the ticking time of the run card counts from. */
    private static String elapsedStart(String body) {
        var side = body.substring(body.indexOf("class=\"status-card-side\""));
        var matcher = Pattern.compile("data-elapsed-start=\"([^\"]+)\"").matcher(side);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
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
