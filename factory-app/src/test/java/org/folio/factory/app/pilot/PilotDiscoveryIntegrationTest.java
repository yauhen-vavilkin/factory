package org.folio.factory.app.pilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * My tasks discovery against real PostgreSQL and a fixture Jira: one anonymous
 * search for the developer's unfinished tasks, all pages read before one
 * transaction replaces the visible snapshot, a failed page marks the scan stale
 * without touching previous rows, and routing stays visible data. There is no
 * background scan; every scan here is an explicit call, as the Scan Jira button
 * makes it.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.pilot.source.assignee=Jane Developer"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotDiscoveryIntegrationTest {

    private static final String INSTANCE = "localhost";
    private static final String FIXTURE_CREATED = "2026-10-01T09:30:00.000+0000";
    private static final String QUERY =
            "assignee = \"Jane Developer\" AND statusCategory != Done ORDER BY updated DESC";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer jira = new WireMockServer(0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jira.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        repository(registry, "mod-bar", "MODBAR");
        repository(registry, "mod-foo", "MODFOO");
        repository(registry, "mod-foo2", "MODFOO");
    }

    private static void repository(DynamicPropertyRegistry registry, String key, String project) {
        String prefix = "factory.dev-factory.repositories." + key + ".";
        registry.add(prefix + "source-repo", () -> "folio-org/" + key);
        registry.add(prefix + "base-branch", () -> "master");
        registry.add(prefix + "build-image", () -> "maven:3.9-eclipse-temurin-21");
        registry.add(prefix + "verification-plan", () -> "java21-unit");
        registry.add(prefix + "jira-projects[0]", () -> project);
    }

    @AfterAll
    static void stopWireMock() {
        jira.stop();
    }

    @Autowired
    PilotDiscoveryService discovery;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetFixture() {
        jira.resetAll();
    }

    @Test
    void completeScanPersistsMyTasksWithRoutingVisibleAsData() {
        stubFullSearch();

        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.discoveredCount()).isEqualTo(5);
        assertThat(result.lastSuccessfulSync()).isNotNull();
        assertThat(result.problem()).isNull();
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertEligible("10001", "MODBAR-1", "repository mod-bar");
        assertIneligible("10002", "MODFOO-2", "Project MODFOO maps to several repositories: mod-foo, mod-foo2");
        assertIneligible("10003", "OTHERP-3",
                "Project OTHERP is not configured: no repository for it in config/developer-repositories.yaml");
        assertEligible("10004", "MODBAR-4", "repository mod-bar");
        assertEligible("10005", "MODBAR-5", "repository mod-bar");

        PilotTask first = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow();
        assertThat(first.getJiraInstance()).isEqualTo(INSTANCE);
        assertThat(first.getProjectKey()).isEqualTo("MODBAR");
        assertThat(first.isInScope()).isTrue();
        assertThat(first.getJiraCreatedAt()).isEqualTo(Instant.parse("2026-10-01T09:30:00Z"));
        JsonNode snapshot = json.readTree(first.getIssueSnapshot());
        assertThat(snapshot.path("key").asString()).isEqualTo("MODBAR-1");
        assertThat(snapshot.path("summary").asString()).isEqualTo("Summary of MODBAR-1");
        assertThat(snapshot.path("status").asString()).isEqualTo("Open");
        assertThat(snapshot.path("components").get(0).asString()).isEqualTo("api");
        assertThat(snapshot.path("truncated")).isEmpty();
        assertThat(first.getSourceRevision()).hasSize(64);

        PilotInstallation installation = installations.findById(1).orElseThrow();
        assertThat(installation.isSyncComplete()).isTrue();
        assertThat(installation.getLastSuccessfulSyncAt()).isNotNull();
        assertThat(installation.getSyncProblem()).isNull();
        assertThat(executions.count()).isZero();
    }

    @Test
    void scanSearchesMyTasksAnonymouslyWithLinksAndCommentsAndNeverWritesToJira() {
        stubFullSearch();

        discovery.sync();

        jira.verify(postRequestedFor(urlEqualTo("/rest/api/2/search/jql"))
                .withHeader("Authorization", absent())
                .withRequestBody(matchingJsonPath("$.jql", equalTo(QUERY)))
                .withRequestBody(matchingJsonPath("$.fields[?(@ == 'issuelinks')]"))
                .withRequestBody(matchingJsonPath("$.fields[?(@ == 'comment')]")));
        assertThat(jira.getAllServeEvents()).allSatisfy(event -> {
            assertThat(event.getRequest().getMethod().getName()).isEqualTo("POST");
            assertThat(event.getRequest().getUrl()).isEqualTo("/rest/api/2/search/jql");
        });
        assertThat(executions.count()).isZero();
    }

    @Test
    void linkedTaskStaysEligibleAndKeepsItsLinksForTheAgent() {
        stubFullSearch();

        discovery.sync();

        PilotTask linked = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10004").orElseThrow();
        assertThat(linked.getEligibility()).isEqualTo(PilotTask.Eligibility.ELIGIBLE);
        JsonNode links = json.readTree(linked.getIssueSnapshot()).path("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).path("key").asString()).isEqualTo("MODBAR-9");
    }

    @Test
    void concurrentInstallationSaveDuringScanDoesNotSurfaceAsScanProblem() throws Exception {
        stubFullSearch();
        assertThat(discovery.sync().complete()).isTrue();
        var concurrentSave = new TransactionTemplate(transactionManager);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Exception> writerFailure = new AtomicReference<>();
        // A dispatch-style installation save (lockById then save) races every scan.
        Thread writer = new Thread(() -> {
            while (running.get()) {
                try {
                    concurrentSave.executeWithoutResult(status -> {
                        PilotInstallation installation = installations.lockById(1).orElse(null);
                        if (installation == null) {
                            return;
                        }
                        installation.setLastSuccessfulSyncAt(Instant.now());
                        installations.save(installation);
                    });
                } catch (Exception e) {
                    writerFailure.set(e);
                    return;
                }
            }
        });
        writer.start();
        try {
            for (int round = 0; round < 5; round++) {
                PilotDiscoveryService.SyncResult result = discovery.sync();
                assertThat(result.complete())
                        .as("scan round %d must not surface the concurrent save as a scan problem: %s",
                                round, result.problem())
                        .isTrue();
            }
        } finally {
            running.set(false);
            writer.join(Duration.ofSeconds(10));
        }

        assertThat(writerFailure).hasValue(null);
        PilotInstallation installation = installations.findById(1).orElseThrow();
        assertThat(installation.isSyncComplete()).isTrue();
        assertThat(installation.getSyncProblem()).isNull();
    }

    @Test
    void renamedKeyWithSameImmutableIssueIdUpdatesOneTask() {
        stubFullSearch();
        discovery.sync();
        PilotTask original = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow();

        jira.resetAll();
        stubSinglePageSearch(issue("10001", "MODBAR-100", "MODBAR", "Renamed summary", ""),
                issue("10005", "MODBAR-5", "MODBAR", "Summary of MODBAR-5", ""));
        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        PilotTask renamed = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow();
        assertThat(renamed.getId()).isEqualTo(original.getId());
        assertThat(renamed.getIssueId()).isEqualTo("10001");
        assertThat(renamed.getDisplayKey()).isEqualTo("MODBAR-100");
        assertThat(renamed.getTitle()).isEqualTo("Renamed summary");
        assertThat(renamed.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(renamed.getSourceRevision()).isNotEqualTo(original.getSourceRevision());
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "MODBAR-100")).isEmpty();
    }

    @Test
    void scanLeavesATaskWithStartedWorkAsItWasAdmittedUntilTheWorkEnds() {
        stubFullSearch();
        discovery.sync();
        PilotTask admitted = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10004").orElseThrow();
        PilotAttempt running = attempts.save(new PilotAttempt(admitted.getId(), 1, PilotAttempt.Kind.INITIAL,
                "started-work-holds", PilotAttempt.State.RUNNING));
        admitted.setInScope(false);
        tasks.save(admitted);

        jira.resetAll();
        stubSinglePageSearch(issue("10004", "OTHERP-40", "OTHERP", "Moved to another project", ""));
        assertThat(discovery.sync().complete()).isTrue();

        assertEligible("10004", "MODBAR-4", "repository mod-bar");
        PilotTask held = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10004").orElseThrow();
        assertThat(held.getTitle()).isEqualTo("Summary of MODBAR-4");
        assertThat(held.getSourceRevision()).isEqualTo(admitted.getSourceRevision());
        assertThat(held.isInScope()).isTrue();

        running.setState(PilotAttempt.State.STOPPED);
        attempts.save(running);
        assertThat(discovery.sync().complete()).isTrue();

        assertIneligible("10004", "OTHERP-40",
                "Project OTHERP is not configured: no repository for it in config/developer-repositories.yaml");
    }

    @Test
    void failedLaterPagePreservesRowsAndLastSuccessAndMarksScanStale() {
        stubFullSearch();
        discovery.sync();
        PilotInstallation healthy = installations.findById(1).orElseThrow();
        Instant lastSync = healthy.getLastSuccessfulSyncAt();
        String revision = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision();

        jira.resetAll();
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .willReturn(okJson("{\"nextPageToken\": \"CURSOR-2\", \"issues\": ["
                        + issue("10001", "MODBAR-1", "MODBAR", "Summary of MODBAR-1", "") + "]}")));
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$.nextPageToken"))
                .willReturn(aResponse().withStatus(500)));

        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isFalse();
        assertThat(result.discoveredCount()).isEqualTo(1);
        assertThat(result.problem()).contains("500");
        assertThat(result.lastSuccessfulSync()).isEqualTo(lastSync);

        PilotInstallation stale = installations.findById(1).orElseThrow();
        assertThat(stale.isSyncComplete()).isFalse();
        assertThat(stale.getSyncProblem()).contains("500");
        assertThat(stale.getLastSuccessfulSyncAt()).isEqualTo(lastSync);
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision())
                .isEqualTo(revision);
    }

    @Test
    void taskNoLongerAssignedKeepsItsHistoryWithoutDeletion() {
        stubFullSearch();
        discovery.sync();
        PilotTask original = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10002").orElseThrow();

        jira.resetAll();
        stubSinglePageSearch(issue("10001", "MODBAR-1", "MODBAR", "Summary of MODBAR-1", ""),
                issue("10005", "MODBAR-5", "MODBAR", "Summary of MODBAR-5", ""));
        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.discoveredCount()).isEqualTo(2);
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10002").orElseThrow().isInScope()).isFalse();
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10003").orElseThrow().isInScope()).isFalse();
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10004").orElseThrow().isInScope()).isFalse();
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().isInScope()).isTrue();
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10002").orElseThrow().getId())
                .isEqualTo(original.getId());
    }

    @Test
    void failedApplyTransactionPreservesRowsAndLastSuccessAndMarksScanStale() {
        stubFullSearch();
        discovery.sync();
        PilotInstallation healthy = installations.findById(1).orElseThrow();
        Instant lastSync = healthy.getLastSuccessfulSyncAt();
        String revision = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision();

        jira.resetAll();
        stubSinglePageSearch(issue("10001", "MODBAR-1", "MODBAR", "Summary of MODBAR-1", ""),
                issue("10006", "MODBAR-" + "X".repeat(70), "MODBAR", "Summary of MODBAR-6", ""));

        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isFalse();
        assertThat(result.discoveredCount()).isEqualTo(2);
        assertThat(result.problem()).isNotBlank();
        assertThat(result.lastSuccessfulSync()).isEqualTo(lastSync);

        PilotInstallation stale = installations.findById(1).orElseThrow();
        assertThat(stale.isSyncComplete()).isFalse();
        assertThat(stale.getSyncProblem()).isNotBlank();
        assertThat(stale.getLastSuccessfulSyncAt()).isEqualTo(lastSync);
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision())
                .isEqualTo(revision);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10006")).isEmpty();
    }

    private void stubFullSearch() {
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$[?(!@.nextPageToken)]"))
                .willReturn(okJson("{\"nextPageToken\": \"CURSOR-2\", \"issues\": ["
                        + issue("10001", "MODBAR-1", "MODBAR", "Summary of MODBAR-1", "") + ", "
                        + issue("10002", "MODFOO-2", "MODFOO", "Summary of MODFOO-2", "") + ", "
                        + issue("10003", "OTHERP-3", "OTHERP", "Summary of OTHERP-3", "") + ", "
                        + issue("10004", "MODBAR-4", "MODBAR", "Summary of MODBAR-4", linked("MODBAR-9"))
                        + "]}")));
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$.nextPageToken"))
                .willReturn(okJson("{\"issues\": ["
                        + issue("10005", "MODBAR-5", "MODBAR", "Summary of MODBAR-5", "") + "]}")));
    }

    private void stubSinglePageSearch(String... issues) {
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$[?(!@.nextPageToken)]"))
                .willReturn(okJson("{\"issues\": [" + String.join(", ", issues) + "]}")));
    }

    private static String issue(String id, String key, String project, String summary, String extraFields) {
        return """
                {"id": "%s", "key": "%s", "fields": {"summary": "%s", "description": "Description of %s", \
                "status": {"name": "Open"}, "issuetype": {"name": "Story"}, "labels": ["backend"], \
                "created": "%s", "components": [{"name": "api"}], \
                "project": {"key": "%s"}%s}}""".formatted(id, key, summary, key, FIXTURE_CREATED, project, extraFields);
    }

    private static String linked(String otherKey) {
        return """
                , "issuelinks": [{"type": {"inward": "relates to", "outward": "relates to"}, \
                "inwardIssue": {"key": "%s", "fields": {"summary": "Preparation", \
                "status": {"name": "Done"}}}}]""".formatted(otherKey);
    }

    private void assertEligible(String issueId, String displayKey, String reason) {
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, issueId).orElseThrow();
        assertThat(task.getDisplayKey()).isEqualTo(displayKey);
        assertThat(task.getEligibility()).isEqualTo(PilotTask.Eligibility.ELIGIBLE);
        assertThat(task.getRoutingReason()).isEqualTo(reason);
    }

    private void assertIneligible(String issueId, String displayKey, String reason) {
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, issueId).orElseThrow();
        assertThat(task.getDisplayKey()).isEqualTo(displayKey);
        assertThat(task.getEligibility()).isEqualTo(PilotTask.Eligibility.INELIGIBLE);
        assertThat(task.getRoutingReason()).isEqualTo(reason);
    }
}
