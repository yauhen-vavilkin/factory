package org.folio.factory.app;

import org.folio.factory.agents.prompt.PromptService;
import org.folio.factory.core.domain.AuditEventType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that actually renders the Thymeleaf templates end to end;
 * standalone MockMvc controller tests never resolve the layout/component
 * fragments. Boots the app, seeds one execution + review, and asserts every
 * UI page renders through the shared layout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
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
    private PromptService promptService;

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
        assertRendered("/reviews", "Reviews");
    }

    @Test
    void executionsListRenders() {
        assertRendered("/executions", "Executions");
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
        String body = assertRendered("/reviews?status=ALL", "Reviews");
        assertThat(body).contains("Signed-off plan").contains("Decided");
    }

    @Test
    void promptsListShowsOverride() {
        String content = promptService.defaultContent("triage-agent", "system") + "\nsmoke tweak";
        promptService.saveOverride("triage-agent", "system", content, "qa-lead");

        String body = assertRendered("/prompts", "Bundled prompt templates");
        assertThat(body).contains("Overridden v1");
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
    void promptEditorRenders() {
        assertRendered("/prompts/triage-agent?file=system", "Version history");
    }

    @Test
    void executionDetailRenders() {
        PipelineExecution execution = executions.save(new PipelineExecution("test-factory", "1", "{}"));
        artifactStore.putMarkdown(execution.getId(), "test_plan.md", "# Plan\n", "test-spec");
        auditLog.record(execution.getId(), AuditEventType.STEP_COMPLETED, "triage", "engine",
                Map.of("note", "done"));

        assertRendered("/executions/" + execution.getId(), "Audit timeline");
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
        context.setVariable("baseUrl", "/executions?status=RUNNING");

        String html = templateEngine.process("fragment-smoke", context);

        assertThat(html)
                .contains("class=\"stepper\"")
                .contains("Triage")
                .contains("retry ×2")
                .contains("class=\"pagination\"")
                .contains("&amp;page=0")
                .contains("&amp;page=2");
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
