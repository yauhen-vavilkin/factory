package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotAutomaticAdmission;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotModeService;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotDiscoveryService;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.connectors.jira.JiraSourceModels.ResolvedSource;
import org.folio.factory.connectors.jira.JiraSourceModels.SourceReference;
import org.folio.factory.connectors.jira.JiraSourceModels.Sprint;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

/**
 * Automatic admission against real PostgreSQL: an explicit enable delegates
 * current eligible tasks and every complete discovery sync delegates new
 * matches, through the same locked admission path as manual selection. A
 * saved-filter, routing or destination authority change returns the
 * installation to manual and invalidates queued approvals — withdrawn initial
 * tasks return for selection, queued retries stop without starting. Automatic
 * initial attempts may consume refreshed input at dispatch within unchanged
 * authority; manual attempts keep the frozen approved input.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S",
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotAutomaticAdmissionIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String BASE_QUERY = "labels = \"team-ready\" ORDER BY created DESC";
    private static final String CHANGED_QUERY = "labels = \"team-ready\" AND priority = High ORDER BY created DESC";
    private static final Instant EARLIER = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-01T10:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoSpyBean
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotAutomaticAdmission automaticAdmission;

    @Autowired
    PilotModeService modeService;

    @Autowired
    PilotJiraInstance jiraInstance;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    DevFactoryProperties devFactory;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final StubSource source = new StubSource();
    private PilotDiscoveryService discovery;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        executions.deleteAll();
        source.reset();
        discovery = discovery(devFactory);
    }

    @Test
    void enableDelegatesCurrentEligibleTasksOnlyOnceAndSkipsAttemptedAndIneligibleOnes() {
        source.issues = List.of(issue("40001", "MODBAR-1", EARLIER), issue("40002", "MODBAR-2", LATER),
                issue("40003", "OTHERP-3", LATER));
        discovery.sync();

        giveTaskAStoppedInitialAttempt("40002");
        var result = enableAutomatic();

        assertThat(result.mode()).isEqualTo(PilotModeService.AdmissionMode.AUTOMATIC);
        assertThat(result.admittedQueueCount()).isEqualTo(1);
        assertThat(result.explanation()).contains("1 eligible task(s)");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40001")))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getKind()).isEqualTo(PilotAttempt.Kind.INITIAL);
                    assertThat(attempt.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.AUTOMATIC);
                    assertThat(attempt.getRequestId()).startsWith("auto-");
                    assertThat(attempt.getScopeFingerprint()).isEqualTo(observed());
                });
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40002")))
                .satisfiesExactly(attempt -> assertThat(attempt.getAdmittedVia())
                        .isEqualTo(PilotAttempt.AdmittedVia.MANUAL));
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40003"))).isEmpty();

        assertThat(automaticAdmission.admitEligible().admittedTaskIds()).isEmpty();
        assertThat(attempts.count()).isEqualTo(2);
    }

    @Test
    void automaticAdmissionDoesNotRequireALabel() {
        source.issues = List.of(issue("40011", "MODBAR-11", EARLIER));
        var firstSync = discovery.sync();
        assertThat(firstSync.complete()).isTrue();

        var result = enableAutomatic();

        assertThat(result.admittedQueueCount()).isEqualTo(1);
        assertThat(source.issuedQueries).hasSize(1);
        assertThat(source.issuedQueries.getFirst()).doesNotContain("AND labels =");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40011"))).hasSize(1);
    }

    @Test
    void unchangedAuthorityKeepsAutomaticModeAcrossSyncsAndAdmitsNewEligibleTasks() {
        source.issues = List.of(issue("40021", "MODBAR-21", EARLIER));
        discovery.sync();
        enableAutomatic();

        discovery = discovery(devFactory);
        source.issues = List.of(issue("40021", "MODBAR-21", EARLIER), issue("40022", "MODBAR-22", LATER));
        discovery.sync();
        PilotInstallation.Mode modeAfterUnchangedRestart =
                installations.findById(1).orElseThrow().getMode();

        assertThat(modeAfterUnchangedRestart).isEqualTo(PilotInstallation.Mode.AUTOMATIC);
        assertThat(installations.findById(1).orElseThrow().getAcceptedAuthorityFingerprint())
                .isEqualTo(observed());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40021")))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED));
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40022")))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getKind()).isEqualTo(PilotAttempt.Kind.INITIAL);
                    assertThat(attempt.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.AUTOMATIC);
                    assertThat(attempt.getScopeFingerprint()).isEqualTo(observed());
                });
    }

    @Test
    void observedAuthorityChangeDuringAdmissionRejectsWithoutCreatingAnAttempt() {
        source.issues = List.of(issue("40111", "MODBAR-111", EARLIER));
        discovery.sync();
        enableAutomatic();
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "40111").orElseThrow();
        PilotAttempt initial = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        initial.setState(PilotAttempt.State.WITHDRAWN);
        initial.setResultReason("pilot source authority changed after admission");
        attempts.save(initial);
        String acceptedBefore = observed();

        TransactionTemplate concurrentAuthorityChange = new TransactionTemplate(transactionManager);
        concurrentAuthorityChange.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            concurrentAuthorityChange.executeWithoutResult(status -> {
                PilotInstallation installation = installations.lockById(1).orElseThrow();
                installation.setObservedAuthorityFingerprint("changed-observed-authority");
                installations.save(installation);
            });
            return tasks.findById(task.getId());
        }).when(tasks).lockById(task.getId());

        var result = automaticAdmission.admitEligible();

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons()).containsEntry(task.getId(),
                "pilot source changed since selection; review and select again");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId()))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.WITHDRAWN));
        PilotInstallation installation = installations.findById(1).orElseThrow();
        assertThat(installation.getObservedAuthorityFingerprint()).isEqualTo("changed-observed-authority");
        assertThat(installation.getAcceptedAuthorityFingerprint()).isEqualTo(acceptedBefore);
        assertThat(installation.getMode()).isEqualTo(PilotInstallation.Mode.AUTOMATIC);
    }

    @Test
    void returningToManualStopsNewAutomaticAdmissionsAndKeepsDelegatedWork() {
        source.issues = List.of(issue("40031", "MODBAR-31", EARLIER));
        discovery.sync();
        enableAutomatic();

        var manual = modeService.setMode(PilotModeService.AdmissionMode.REVIEW_BEFORE_RUN, null);

        assertThat(manual.mode()).isEqualTo(PilotModeService.AdmissionMode.REVIEW_BEFORE_RUN);
        assertThat(manual.admittedQueueCount()).isZero();
        source.issues = List.of(issue("40031", "MODBAR-31", EARLIER), issue("40032", "MODBAR-32", LATER));
        discovery.sync();

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40031")))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED));
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40032"))).isEmpty();
        assertThat(automaticAdmission.admitEligible().admittedTaskIds()).isEmpty();
    }

    @Test
    void filterChangeRevertsToManualInvalidatesQueuedInitialApprovalsAndKeepsObservedForDisplay() {
        source.issues = List.of(issue("40041", "MODBAR-41", EARLIER));
        discovery.sync();
        enableAutomatic();
        String acceptedBefore = observed();

        source.query = CHANGED_QUERY;
        source.issues = List.of(issue("40041", "MODBAR-41", EARLIER), issue("40042", "MODBAR-42", LATER));
        discovery.sync();
        PilotInstallation.Mode modeAfterFilterChange = installations.findById(1).orElseThrow().getMode();

        assertThat(modeAfterFilterChange).isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        assertThat(installations.findById(1).orElseThrow().getObservedAuthorityFingerprint())
                .isNotEqualTo(acceptedBefore);
        assertThat(installations.findById(1).orElseThrow().getAcceptedAuthorityFingerprint())
                .isEqualTo(acceptedBefore);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40041")))
                .singleElement()
                .satisfies(withdrawn -> {
                    assertThat(withdrawn.getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
                    assertThat(withdrawn.getExecutionId()).isNull();
                    assertThat(withdrawn.getResultReason()).contains("authority changed", "select");
                });
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40042"))).isEmpty();

        PilotTask returned = tasks.findByJiraInstanceAndIssueId(INSTANCE, "40041").orElseThrow();
        var reselected = admissions.runSelected(new PilotCommands.RunSelected(
                "run-return", observed(), List.of(selectionFor(returned))));
        assertThat(reselected.admittedTaskIds()).containsExactly(returned.getId());
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(returned.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
        assertThat(history.get(1).getState()).isEqualTo(PilotAttempt.State.RESERVED);
    }

    @Test
    void authorityChangeStopsAQueuedRetryInsteadOfStartingIt() {
        source.issues = List.of(issue("40051", "MODBAR-51", EARLIER));
        discovery.sync();
        PilotTask retryTask = tasks.findByJiraInstanceAndIssueId(INSTANCE, "40051").orElseThrow();
        var admitted = admissions.runSelected(new PilotCommands.RunSelected(
                "run-retry-setup", observed(), List.of(selectionFor(retryTask))));
        assertThat(admitted.admittedTaskIds()).containsExactly(retryTask.getId());
        PilotAttempt initial = attempts.findByTaskIdOrderByOrdinalAsc(retryTask.getId()).getFirst();
        initial.setState(PilotAttempt.State.STOPPED);
        initial.setResultReason("coding: verification failed");
        attempts.save(initial);
        PilotAttempt retry = new PilotAttempt(retryTask.getId(), 2, PilotAttempt.Kind.RETRY,
                "run-retry#" + retryTask.getId(), PilotAttempt.State.RESERVED);
        retry.setInputFingerprint(initial.getInputFingerprint());
        retry.setScopeFingerprint(observed());
        retry.setDestinationFingerprint(initial.getDestinationFingerprint());
        retry.setApprovedSnapshot(initial.getApprovedSnapshot());
        attempts.save(retry);
        enableAutomatic();

        source.query = CHANGED_QUERY;
        discovery.sync();

        assertThat(attempts.findById(retry.getId()).orElseThrow().getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(attempts.findById(retry.getId()).orElseThrow().getResultReason())
                .contains("authority changed", "Retry");
        assertThat(attempts.findById(retry.getId()).orElseThrow().getExecutionId()).isNull();
        assertThat(dispatcher.dispatchNext()).isEmpty();
        assertThat(executions.count()).isZero();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void reEnablingAfterChangeAcceptsTheNewAuthorityAndReDelegatesWithdrawnTasks() {
        source.issues = List.of(issue("40061", "MODBAR-61", EARLIER), issue("40062", "MODBAR-62", LATER));
        discovery.sync();
        enableAutomatic();
        source.query = CHANGED_QUERY;
        source.issues = List.of(issue("40061", "MODBAR-61", EARLIER), issue("40062", "MODBAR-62", LATER),
                issue("40063", "MODBAR-63", LATER));
        discovery.sync();
        String newAuthority = observed();

        var reEnabled = modeService.setMode(PilotModeService.AdmissionMode.AUTOMATIC, newAuthority);

        assertThat(reEnabled.mode()).isEqualTo(PilotModeService.AdmissionMode.AUTOMATIC);
        assertThat(reEnabled.admittedQueueCount()).isEqualTo(3);
        PilotInstallation installation = installations.findById(1).orElseThrow();
        assertThat(installation.getAcceptedAuthorityFingerprint()).isEqualTo(newAuthority);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40061"))).hasSize(2);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40061")))
                .last()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getScopeFingerprint()).isEqualTo(newAuthority);
                });
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40062"))).hasSize(2);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40063"))).hasSize(1);
    }

    @Test
    void routingDestinationChangeAlsoRevertsAndInvalidates() {
        source.issues = List.of(issue("40071", "MODBAR-71", EARLIER));
        discovery.sync();
        enableAutomatic();
        String acceptedBefore = observed();

        var movedRepository = new TreeMap<>(devFactory.repositories());
        movedRepository.put("mod-bar", new DevFactoryProperties.Repository("folio-org/mod-bar-moved",
                "master", "maven:3.9-eclipse-temurin-21", "java21-unit", List.of("MODBAR"), List.of()));
        PilotDiscoveryService afterRoutingChange = discovery(
                new DevFactoryProperties(devFactory.gitBaseUrl(), movedRepository));
        afterRoutingChange.sync();

        assertThat(installations.findById(1).orElseThrow().getMode())
                .isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        assertThat(installations.findById(1).orElseThrow().getObservedAuthorityFingerprint())
                .isNotEqualTo(acceptedBefore);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId("40071")))
                .singleElement()
                .satisfies(withdrawn -> assertThat(withdrawn.getState()).isEqualTo(PilotAttempt.State.WITHDRAWN));
    }

    @Test
    void automaticInitialConsumesRefreshedInputAtDispatchWhileManualStaysFrozen() {
        source.issues = List.of(issue("40081", "MODBAR-81", EARLIER), issue("40082", "MODBAR-82", LATER));
        discovery.sync();
        PilotTask manual = tasks.findByJiraInstanceAndIssueId(INSTANCE, "40082").orElseThrow();
        admissions.runSelected(new PilotCommands.RunSelected(
                "run-manual", observed(), List.of(selectionFor(manual))));
        enableAutomatic();
        String frozenInput = attempts.findByTaskIdOrderByOrdinalAsc(manual.getId())
                .getFirst().getInputFingerprint();

        source.issues = List.of(issueWithSummary("40081", "MODBAR-81", EARLIER, "Changed summary for 81"),
                issueWithSummary("40082", "MODBAR-82", LATER, "Changed summary for 82"));
        discovery.sync();

        assertThat(installations.findById(1).orElseThrow().getMode()).isEqualTo(PilotInstallation.Mode.AUTOMATIC);
        String refreshedInput = tasks.findByJiraInstanceAndIssueId(INSTANCE, "40081").orElseThrow()
                .getSourceRevision();
        assertThat(refreshedInput).isNotEqualTo(frozenInput);

        assertThat(dispatcher.dispatchNext()).isPresent();

        PilotAttempt automatic = attempts.findByTaskIdOrderByOrdinalAsc(taskId("40081")).getFirst();
        assertThat(automatic.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(automatic.getInputFingerprint()).isEqualTo(refreshedInput);
        assertThat(automatic.getApprovedSnapshot())
                .isEqualTo(tasks.findByJiraInstanceAndIssueId(INSTANCE, "40081").orElseThrow().getIssueSnapshot());
        PilotAttempt frozen = attempts.findByTaskIdOrderByOrdinalAsc(manual.getId()).getFirst();
        assertThat(frozen.getState()).isEqualTo(PilotAttempt.State.RESERVED);
        assertThat(frozen.getInputFingerprint()).isEqualTo(frozenInput)
                .isNotEqualTo(tasks.findById(manual.getId()).orElseThrow().getSourceRevision());
    }

    @Test
    void staleDiscoveryBlocksNewAutomaticAdmissionsAndEnable() {
        source.issues = List.of(issue("40091", "MODBAR-91", EARLIER));
        discovery.sync();
        PilotInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        assertThat(automaticAdmission.admitEligible().admittedTaskIds()).isEmpty();
        assertThatThrownBy(() -> modeService.setMode(PilotModeService.AdmissionMode.AUTOMATIC, observed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync is incomplete", "search page 2 failed");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void enableWithStaleOrMissingFingerprintIsRejected() {
        source.issues = List.of(issue("40101", "MODBAR-101", EARLIER));
        discovery.sync();
        String observed = observed();

        assertThatThrownBy(() -> modeService.setMode(PilotModeService.AdmissionMode.AUTOMATIC, "stale-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed");
        assertThatThrownBy(() -> modeService.setMode(PilotModeService.AdmissionMode.AUTOMATIC, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires the observed pilot source authority fingerprint");
        assertThat(installations.findById(1).orElseThrow().getMode())
                .isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        assertThat(attempts.count()).isZero();
        assertThat(observed).isNotBlank();
    }

    private PilotDiscoveryService discovery(DevFactoryProperties repositories) {
        return new PilotDiscoveryService(source, new PilotSourceProperties("981", "4711",
                List.of("To Do"), null, null, null), new RepositoryPolicy(repositories), repositories,
                tasks, installations, transactionManager, modeService, automaticAdmission, jiraInstance);
    }

    private PilotModeService.ModeResult enableAutomatic() {
        return modeService.setMode(PilotModeService.AdmissionMode.AUTOMATIC, observed());
    }

    private String observed() {
        return installations.findById(1).orElseThrow().getObservedAuthorityFingerprint();
    }

    private void giveTaskAStoppedInitialAttempt(String issueId) {
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, issueId).orElseThrow();
        var result = admissions.runSelected(new PilotCommands.RunSelected(
                "run-stopped-" + issueId, observed(), List.of(selectionFor(task))));
        assertThat(result.admittedTaskIds()).as(String.valueOf(result.rejectedReasons()))
                .containsExactly(task.getId());
        PilotAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        attempt.setState(PilotAttempt.State.STOPPED);
        attempt.setResultReason("coding: verification failed");
        attempts.save(attempt);
    }

    private java.util.UUID taskId(String issueId) {
        return tasks.findByJiraInstanceAndIssueId(INSTANCE, issueId).orElseThrow().getId();
    }

    private PilotCommands.Selection selectionFor(PilotTask task) {
        return new PilotCommands.Selection(task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task));
    }

    private static DiscoveredIssue issue(String id, String key, Instant createdAt) {
        return issueWithSummary(id, key, createdAt, "Summary of " + key);
    }

    private static DiscoveredIssue issueWithSummary(String id, String key, Instant createdAt, String summary) {
        String projectKey = key.substring(0, key.indexOf('-'));
        return new DiscoveredIssue(id, key, projectKey, List.of("api"), createdAt,
                new JiraIssue(key, summary, "Description of " + key, "To Do", "Story",
                        List.of("backend"), null));
    }

    private static final class StubSource implements JiraSourceConnector {

        private String query = BASE_QUERY;
        private List<DiscoveredIssue> issues = List.of();
        private final List<String> issuedQueries = new ArrayList<>();

        void reset() {
            query = BASE_QUERY;
            issues = List.of();
            issuedQueries.clear();
        }

        @Override
        public SourceReference parseSourceReference(String raw) {
            return new SourceReference(JiraSourceModels.Kind.BOARD, "981");
        }

        @Override
        public ResolvedSource resolveSource(SourceReference reference) {
            return new ResolvedSource("981", "Team board",
                    URI.create("https://jira.example.org/jira/software/c/boards/981"), "2001", query);
        }

        @Override
        public Sprint getSprint(String sprintId) {
            return new Sprint("4711", "Sprint 12");
        }

        @Override
        public IssuePage searchIssues(String jql, String cursor) {
            issuedQueries.add(jql);
            return new IssuePage(issues, null);
        }
    }
}
