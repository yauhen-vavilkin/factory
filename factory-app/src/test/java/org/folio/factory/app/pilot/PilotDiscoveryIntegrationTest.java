package org.folio.factory.app.pilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.app.StubLlmConfiguration;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pilot discovery against real PostgreSQL and a fixture Jira: all pages are read
 * before one transaction replaces the visible snapshot, a failed page marks the
 * sync stale without touching previous rows, eligibility stays visible data, and
 * the enabled five-minute callback only discovers. The scheduler runs at 250 ms;
 * every awaited state is only reachable once the fixture stubs are in place.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.pilot.discovery.enabled=true",
        "factory.pilot.discovery.poll-interval-ms=250"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotDiscoveryIntegrationTest {

    private static final String INSTANCE = "localhost";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer jira = new WireMockServer(0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        jira.start();
        registry.add("factory.connectors.jira.base-url", jira::baseUrl);
        registry.add("factory.connectors.jira.email", () -> "bot@example.org");
        registry.add("factory.connectors.jira.api-token", () -> "token");
        registry.add("factory.pilot.source.source", () -> "981");
        registry.add("factory.pilot.source.sprint-id", () -> "4711");
        registry.add("factory.pilot.source.statuses[0]", () -> "To Do");
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
    PipelineExecutionRepository executions;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void resetFixture() {
        jira.resetAll();
    }

    @Test
    void completeSyncPersistsTasksWithEligibilityAndRoutingVisibleAsData() {
        stubSource();
        stubFullSearch();

        awaitSyncComplete();
        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.discoveredCount()).isEqualTo(5);
        assertThat(result.lastSuccessfulSync()).isNotNull();
        assertThat(result.problem()).isNull();
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertEligible("10001", "MODBAR-1", "repository mod-bar");
        assertIneligible("10002", "MODFOO-2", "ambiguous repository mapping: mod-foo, mod-foo2");
        assertIneligible("10003", "OTHERP-3", "no repository mapping for project OTHERP");
        assertIneligible("10004", "MODBAR-4", "known dependency: MODBAR-9");
        assertEligible("10005", "MODBAR-5", "repository mod-bar");

        PilotTask first = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow();
        assertThat(first.getJiraInstance()).isEqualTo(INSTANCE);
        assertThat(first.getProjectKey()).isEqualTo("MODBAR");
        assertThat(first.isInScope()).isTrue();
        JsonNode snapshot = json.readTree(first.getIssueSnapshot());
        assertThat(snapshot.path("key").asString()).isEqualTo("MODBAR-1");
        assertThat(snapshot.path("summary").asString()).isEqualTo("Summary of MODBAR-1");
        assertThat(snapshot.path("components").get(0).asString()).isEqualTo("api");
        assertThat(snapshot.path("truncated")).isEmpty();
        assertThat(first.getSourceRevision()).hasSize(64);

        PilotInstallation installation = installations.findById(1).orElseThrow();
        assertThat(installation.getMode()).isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        assertThat(installation.isSyncComplete()).isTrue();
        assertThat(installation.getLastSuccessfulSyncAt()).isNotNull();
        assertThat(installation.getAcceptedAuthorityFingerprint()).hasSize(64);
        assertThat(installation.getSyncProblem()).isNull();
        assertThat(executions.count()).isZero();
    }

    @Test
    void renamedKeyWithSameImmutableIssueIdUpdatesOneTask() {
        stubSource();
        stubFullSearch();
        awaitSyncComplete();
        PilotTask original = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow();

        jira.resetAll();
        stubSource();
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
    void failedLaterPagePreservesRowsAndLastSuccessAndMarksSyncStale() {
        stubSource();
        stubFullSearch();
        awaitSyncComplete();
        PilotInstallation healthy = installations.findById(1).orElseThrow();
        Instant lastSync = healthy.getLastSuccessfulSyncAt();
        String revision = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision();

        jira.resetAll();
        stubSource();
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
        assertThat(stale.getAcceptedAuthorityFingerprint()).isEqualTo(healthy.getAcceptedAuthorityFingerprint());
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision())
                .isEqualTo(revision);
    }

    @Test
    void taskLeavingTheFilterKeepsItsHistoryWithoutDeletion() {
        stubSource();
        stubFullSearch();
        awaitSyncComplete();
        PilotTask original = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10002").orElseThrow();

        jira.resetAll();
        stubSource();
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
    void routingReasonBeyondTheColumnLimitStillSyncsAndIsTruncated() {
        stubSource();
        stubFullSearch();
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$.nextPageToken"))
                .willReturn(okJson("{\"issues\": ["
                        + issue("10005", "MODBAR-5", "MODBAR", "Summary of MODBAR-5", linkedDependencies(20))
                        + "]}")));

        awaitSyncComplete();
        PilotDiscoveryService.SyncResult result = discovery.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.problem()).isNull();
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10005").orElseThrow();
        assertThat(task.getEligibility()).isEqualTo(PilotTask.Eligibility.INELIGIBLE);
        assertThat(task.getRoutingReason()).startsWith("known dependency: ").hasSize(500);
    }

    @Test
    void failedApplyTransactionPreservesRowsAndLastSuccessAndMarksSyncStale() {
        stubSource();
        stubFullSearch();
        awaitSyncComplete();
        PilotInstallation healthy = installations.findById(1).orElseThrow();
        Instant lastSync = healthy.getLastSuccessfulSyncAt();
        String revision = tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision();

        jira.resetAll();
        stubSource();
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
        assertThat(stale.getAcceptedAuthorityFingerprint()).isEqualTo(healthy.getAcceptedAuthorityFingerprint());
        assertThat(tasks.countByJiraInstance(INSTANCE)).isEqualTo(5);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10001").orElseThrow().getSourceRevision())
                .isEqualTo(revision);
        assertThat(tasks.findByJiraInstanceAndIssueId(INSTANCE, "10006")).isEmpty();
    }

    @Test
    void scheduledPollerDiscoversWithoutAdmittingOrWritingToJira() {
        stubSource();
        stubFullSearch();
        markSyncStale();

        awaitSyncComplete();

        assertThat(executions.count()).isZero();
        assertThat(jira.getAllServeEvents()).isNotEmpty();
        assertThat(jira.getAllServeEvents()).allSatisfy(event ->
                assertThat(event.getRequest().getUrl()).containsAnyOf(
                        "/rest/agile/1.0/board/981/filter",
                        "/rest/agile/1.0/sprint/4711",
                        "/rest/api/2/search/jql"));
    }

    private void awaitSyncComplete() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(installations.findById(1).orElseThrow().isSyncComplete()).isTrue());
    }

    private void markSyncStale() {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(false);
        installations.save(installation);
    }

    private void stubSource() {
        jira.stubFor(get(urlEqualTo("/rest/agile/1.0/board/981/filter"))
                .willReturn(okJson("""
                        {"id": "2001", "name": "Team board",
                         "jql": "labels = \\"team-ready\\" ORDER BY created DESC"}""")));
        jira.stubFor(get(urlEqualTo("/rest/agile/1.0/sprint/4711"))
                .willReturn(okJson("{\"id\": \"4711\", \"name\": \"Sprint 12\"}")));
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
                "status": {"name": "To Do"}, "issuetype": {"name": "Story"}, "labels": ["backend"], \
                "created": "2026-10-01T09:30:00.000+0000", "components": [{"name": "api"}], \
                "project": {"key": "%s"}%s}}""".formatted(id, key, summary, key, project, extraFields);
    }

    private static String linked(String otherKey) {
        return ", \"issuelinks\": [" + linkJson(otherKey) + "]";
    }

    private static String linkJson(String otherKey) {
        return """
                {"type": {"inward": "is blocked by", "outward": "blocks"}, \
                "inwardIssue": {"key": "%s", "fields": {"summary": "Preparation", \
                "status": {"name": "Done"}}}}""".formatted(otherKey);
    }

    private static String linkedDependencies(int count) {
        StringBuilder links = new StringBuilder(", \"issuelinks\": [");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                links.append(", ");
            }
            links.append(linkJson("VERYLONGDEPENDENCYKEYPREFIX" + i));
        }
        return links.append("]").toString();
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
