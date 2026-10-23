package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotAutomaticAdmission;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotModeService;
import org.folio.factory.app.pilot.admission.PilotRetryService;
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
import org.folio.factory.core.service.StateManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicit fresh Retry against real PostgreSQL: a STOPPED latest attempt can
 * retry, and a withdrawn latest attempt stays retriable once earlier real work
 * exists. The retry freezes the current bounded input and source authority
 * into a new RETRY attempt whose launch reuses the serial dispatch path for a
 * new execution, and duplicate or stale clicks never create a second attempt.
 * A queued retry whose approved input/destination/scope changed before start
 * stops instead of launching, in both admission modes, and only another
 * explicit Retry admits fresh work.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S",
        "factory.pilot.execution.deadline=PT30M",
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotRetryIntegrationTest {

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

    @Autowired
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
    PilotRetryService retries;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    PilotJiraInstance jiraInstance;

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
    void retryOfStoppedAttemptReservesFreshAttemptAndLaunchesANewExecution() {
        source.issues = List.of(issue("50001", "MODBAR-501", EARLIER));
        discovery.sync();
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50001").orElseThrow();
        stopWithFailedInitialAttempt(task, "run-501");
        UUID previousAttemptId = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst().getId();

        var click = retries.retry(new PilotCommands.Retry("retry-501", task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task)));
        UUID newAttemptId = click.attemptId();

        assertThat(newAttemptId).isNotEqualTo(previousAttemptId);
        assertThat(click.state()).isEqualTo("QUEUED");
        assertThat(click.executionId()).isNotNull();
        var execution = stateManager.get(click.executionId());
        assertThat(execution.getFlowId()).isEqualTo("dev-factory");
        assertThat(execution.getTriggerPayload()).contains("MODBAR-501");
        assertThat(execution.getDedupKey()).isEqualTo("pilot-attempt=" + newAttemptId);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(newAttemptId);

        List<PilotAttempt> attemptHistory = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        assertThat(attemptHistory).hasSize(2);
        PilotAttempt retry = attemptHistory.get(1);
        assertThat(retry.getKind()).isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(retry.getOrdinal()).isEqualTo(2);
        assertThat(retry.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.MANUAL);
        assertThat(retry.getInputFingerprint()).isEqualTo(PilotAdmissionService.inputFingerprint(task));
        assertThat(retry.getScopeFingerprint()).isEqualTo(observed());
        assertThat(retry.getDestinationFingerprint())
                .isEqualTo(PilotAdmissionService.destinationFingerprint(task));
        assertThat(retry.getApprovedSnapshot()).isEqualTo(task.getIssueSnapshot());
        assertThat(retry.getStartedAt()).isNotNull();
        assertThat(retry.getDeadlineAt()).isEqualTo(retry.getStartedAt().plus(Duration.ofMinutes(30)));

        PilotAttempt previous = attemptHistory.get(0);
        assertThat(previous.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(previous.getResultReason()).isEqualTo("coding: verification failed");
        assertThat(previous.getExecutionId()).isNull();
    }

    @Test
    void duplicateRetryClickResolvesToTheStoredAttemptWithoutCreatingAnotherOne() {
        source.issues = List.of(issue("50011", "MODBAR-511", EARLIER));
        discovery.sync();
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50011").orElseThrow();
        stopWithFailedInitialAttempt(task, "run-511");

        var firstClick = retries.retry(new PilotCommands.Retry("retry-511", task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task)));
        var secondClick = retries.retry(new PilotCommands.Retry("retry-511", task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task)));

        assertThat(secondClick.attemptId()).isEqualTo(firstClick.attemptId());
        assertThat(secondClick.executionId()).isEqualTo(firstClick.executionId());
        assertThat(secondClick.state()).isEqualTo("QUEUED");
        assertThat(executions.count()).isEqualTo(1);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(2);
    }

    @Test
    void retryEligibilityFollowsTheLatestAttemptStateAndCurrentScope() {
        source.issues = List.of(issue("50021", "MODBAR-521", EARLIER));
        discovery.sync();

        PilotTask queued = directTask("50022", "MODBAR-522");
        admit(queued, "run-522");
        PilotTask running = directTask("50023", "MODBAR-523");
        admit(running, "run-523");
        forceState(running, PilotAttempt.State.RUNNING, null);
        PilotTask inspecting = directTask("50024", "MODBAR-524");
        admit(inspecting, "run-524");
        forceState(inspecting, PilotAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");
        PilotTask delivered = directTask("50025", "MODBAR-525");
        admit(delivered, "run-525");
        forceState(delivered, PilotAttempt.State.PR_READY, null);
        PilotTask withdrawn = directTask("50026", "MODBAR-526");
        admit(withdrawn, "run-526");
        forceState(withdrawn, PilotAttempt.State.WITHDRAWN, "task left the pilot scope before start");
        PilotTask neverAttempted = directTask("50027", "MODBAR-527");
        PilotTask outOfScope = directTask("50028", "MODBAR-528");
        stopWithFailedInitialAttempt(outOfScope, "run-528");
        PilotTask saved = tasks.findById(outOfScope.getId()).orElseThrow();
        saved.setInScope(false);
        outOfScope = tasks.save(saved);
        PilotTask unsupported = directTask("50029", "MODBAR-529");
        stopWithFailedInitialAttempt(unsupported, "run-529");
        PilotTask routed = tasks.findById(unsupported.getId()).orElseThrow();
        routed.setEligibility(PilotTask.Eligibility.INELIGIBLE);
        unsupported = tasks.save(routed);

        assertThat(retryRejected(queued, "live attempt")).isEqualTo("RESERVED");
        assertThat(retryRejected(running, "live attempt")).isEqualTo("RUNNING");
        assertThat(retryRejected(inspecting, "needs inspection")).isEqualTo("NEEDS_INSPECTION");
        assertThat(retryRejected(delivered, "delivered a pull request")).isEqualTo("PR_READY");
        assertThat(retryRejected(withdrawn, "select the task again")).isEqualTo("WITHDRAWN");
        assertThat(retryRejected(neverAttempted, "never been attempted")).isNull();
        assertThat(retryRejected(outOfScope, "outside the current pilot scope")).isEqualTo("STOPPED");
        assertThat(retryRejected(unsupported, "not eligible")).isEqualTo("STOPPED");

        PilotTask stale = directTask("50030", "MODBAR-530");
        stopWithFailedInitialAttempt(stale, "run-530");
        var staleClick = retries.retry(new PilotCommands.Retry("retry-530", stale.getId(), stale.getVersion(),
                "stale-input-fingerprint", PilotAdmissionService.destinationFingerprint(stale)));
        assertThat(staleClick.explanation()).contains("changed since the retry was requested");
        assertThat(staleClick.attemptId())
                .isEqualTo(attempts.findByTaskIdOrderByOrdinalAsc(stale.getId()).getFirst().getId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(stale.getId())).hasSize(1);

        assertThat(attempts.count()).isEqualTo(8);
        assertThat(executions.count()).isZero();
    }

    @Test
    void staleDiscoveryBlocksRetryAdmission() {
        source.issues = List.of(issue("50041", "MODBAR-541", EARLIER));
        discovery.sync();
        PilotTask task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50041").orElseThrow();
        stopWithFailedInitialAttempt(task, "run-541");
        PilotInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        var click = retries.retry(new PilotCommands.Retry("retry-541", task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task)));

        assertThat(click.attemptId())
                .isEqualTo(attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst().getId());
        assertThat(click.explanation()).contains("sync is incomplete", "search page 2 failed");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(1);
        assertThat(executions.count()).isZero();
    }

    @Test
    void changedQueuedRetryStopsInsteadOfStartingInManualModeAndRequiresAnotherExplicitRetry() {
        source.issues = List.of(issue("50051", "MODBAR-551", EARLIER), issue("50052", "MODBAR-552", LATER));
        discovery.sync();
        PilotTask target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50051").orElseThrow();
        PilotTask holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50052").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-551");
        admitAndDispatch(holder, "run-552");
        String approvedInput = PilotAdmissionService.inputFingerprint(target);

        var firstClick = retries.retry(new PilotCommands.Retry("retry-551", target.getId(), target.getVersion(),
                approvedInput, PilotAdmissionService.destinationFingerprint(target)));
        assertThat(firstClick.state()).isEqualTo("RESERVED");
        assertThat(firstClick.executionId()).isNull();

        source.issues = List.of(issueWithSummary("50051", "MODBAR-551", EARLIER, "Revised summary of 551"),
                issue("50052", "MODBAR-552", LATER));
        discovery.sync();
        PilotTask changed = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50051").orElseThrow();
        assertThat(changed.getSourceRevision()).isNotEqualTo(approvedInput);

        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isEmpty();

        PilotAttempt queuedRetry = attempts.findById(firstClick.attemptId()).orElseThrow();
        assertThat(queuedRetry.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(queuedRetry.getResultReason()).contains("input changed", "run a fresh explicit Retry");
        assertThat(queuedRetry.getExecutionId()).isNull();
        assertThat(executions.count()).isEqualTo(1);
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(changed.getId()))
                .satisfiesExactly(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.STOPPED),
                        attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.STOPPED));

        var changedQueuedRetry = retries.retry(new PilotCommands.Retry("retry-551", changed.getId(),
                changed.getVersion(), approvedInput, PilotAdmissionService.destinationFingerprint(changed)));
        assertThat(changedQueuedRetry.state()).isEqualTo("STOPPED");
        assertThat(changedQueuedRetry.attemptId()).isEqualTo(firstClick.attemptId());
        assertThat(changedQueuedRetry.executionId()).isNull();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(changed.getId())).hasSize(2);

        var freshRetry = retries.retry(new PilotCommands.Retry("retry-551-again", changed.getId(),
                changed.getVersion(), PilotAdmissionService.inputFingerprint(changed),
                PilotAdmissionService.destinationFingerprint(changed)));
        assertThat(freshRetry.state()).isEqualTo("QUEUED");
        assertThat(freshRetry.executionId()).isNotNull();
        assertThat(attempts.findById(freshRetry.attemptId()).orElseThrow().getInputFingerprint())
                .isEqualTo(changed.getSourceRevision());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(changed.getId())).hasSize(3);
    }

    @Test
    void changedQueuedRetryAlsoStopsInAutomaticModeAndAttemptedTasksStayOutOfInitialSelection() {
        source.issues = List.of(issue("50061", "MODBAR-561", EARLIER), issue("50062", "MODBAR-562", LATER));
        discovery.sync();
        PilotTask target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50061").orElseThrow();
        PilotTask holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50062").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-561");
        admitAndDispatch(holder, "run-562");
        enableAutomatic();

        var firstClick = retries.retry(new PilotCommands.Retry("retry-561", target.getId(), target.getVersion(),
                PilotAdmissionService.inputFingerprint(target),
                PilotAdmissionService.destinationFingerprint(target)));
        assertThat(firstClick.state()).isEqualTo("RESERVED");
        PilotAttempt queuedRetry = attempts.findById(firstClick.attemptId()).orElseThrow();
        assertThat(queuedRetry.getKind()).isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(queuedRetry.getAdmittedVia()).isEqualTo(PilotAttempt.AdmittedVia.MANUAL);

        source.issues = List.of(issueWithSummary("50061", "MODBAR-561", EARLIER, "Revised summary of 561"),
                issue("50062", "MODBAR-562", LATER));
        discovery.sync();
        assertThat(installations.findById(1).orElseThrow().getMode())
                .isEqualTo(PilotInstallation.Mode.AUTOMATIC);

        var delegated = automaticAdmission.admitEligible();
        assertThat(delegated.admittedTaskIds()).doesNotContain(target.getId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(target.getId())).hasSize(2);

        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isEmpty();

        PilotAttempt stopped = attempts.findById(firstClick.attemptId()).orElseThrow();
        assertThat(stopped.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(stopped.getResultReason()).contains("no longer matches", "run a fresh explicit Retry");
        assertThat(stopped.getExecutionId()).isNull();
        assertThat(executions.count()).isEqualTo(1);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void invalidatedQueuedRetryRequiresAndAllowsAFreshExplicitRetry() {
        source.issues = List.of(issue("50071", "MODBAR-571", EARLIER), issue("50072", "MODBAR-572", LATER));
        discovery.sync();
        PilotTask target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50071").orElseThrow();
        PilotTask holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50072").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-571");
        admitAndDispatch(holder, "run-572");
        enableAutomatic();

        var firstClick = retries.retry(new PilotCommands.Retry("retry-571", target.getId(), target.getVersion(),
                PilotAdmissionService.inputFingerprint(target),
                PilotAdmissionService.destinationFingerprint(target)));
        assertThat(firstClick.state()).isEqualTo("RESERVED");

        source.query = CHANGED_QUERY;
        discovery.sync();
        assertThat(installations.findById(1).orElseThrow().getMode())
                .isEqualTo(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        PilotAttempt invalidated = attempts.findById(firstClick.attemptId()).orElseThrow();
        assertThat(invalidated.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(invalidated.getResultReason()).contains("authority changed", "run a fresh explicit Retry");
        assertThat(invalidated.getExecutionId()).isNull();

        PilotTask current = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50071").orElseThrow();
        var freshClick = retries.retry(new PilotCommands.Retry("retry-571-again", current.getId(),
                current.getVersion(), PilotAdmissionService.inputFingerprint(current),
                PilotAdmissionService.destinationFingerprint(current)));
        assertThat(freshClick.state()).isEqualTo("RESERVED");
        assertThat(freshClick.executionId()).isNull();

        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isPresent();
        List<PilotAttempt> attemptHistory = attempts.findByTaskIdOrderByOrdinalAsc(current.getId());
        assertThat(attemptHistory).hasSize(3);
        PilotAttempt third = attemptHistory.get(2);
        assertThat(third.getKind()).isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(third.getOrdinal()).isEqualTo(3);
        assertThat(third.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(third.getScopeFingerprint()).isEqualTo(observed());
        assertThat(third.getExecutionId()).isNotNull();
        assertThat(attemptHistory.get(0).getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(attemptHistory.get(1).getState()).isEqualTo(PilotAttempt.State.STOPPED);
    }

    @Test
    void withdrawnQueuedRetryKeepsTheTaskRetriableWhenItReturnsToScope() {
        source.issues = List.of(issue("50081", "MODBAR-581", EARLIER), issue("50082", "MODBAR-582", LATER));
        discovery.sync();
        PilotTask target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50081").orElseThrow();
        PilotTask holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50082").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-581");
        admitAndDispatch(holder, "run-582");

        var firstRetry = retries.retry(new PilotCommands.Retry("retry-581", target.getId(), target.getVersion(),
                PilotAdmissionService.inputFingerprint(target),
                PilotAdmissionService.destinationFingerprint(target)));
        assertThat(firstRetry.state()).isEqualTo("RESERVED");

        PilotTask left = tasks.findById(target.getId()).orElseThrow();
        left.setInScope(false);
        tasks.save(left);
        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isEmpty();

        PilotAttempt withdrawnRetry = attempts.findById(firstRetry.attemptId()).orElseThrow();
        assertThat(withdrawnRetry.getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
        assertThat(withdrawnRetry.getResultReason()).isEqualTo("task left the pilot scope before start");
        assertThat(withdrawnRetry.getExecutionId()).isNull();
        assertThat(executions.count()).isEqualTo(1);

        PilotTask returned = tasks.findById(target.getId()).orElseThrow();
        returned.setInScope(true);
        PilotTask backInScope = tasks.save(returned);
        var freshRetry = retries.retry(new PilotCommands.Retry("retry-581-again", backInScope.getId(),
                backInScope.getVersion(), PilotAdmissionService.inputFingerprint(backInScope),
                PilotAdmissionService.destinationFingerprint(backInScope)));
        assertThat(freshRetry.attemptId()).isNotEqualTo(firstRetry.attemptId());
        assertThat(freshRetry.state()).isEqualTo("QUEUED");
        assertThat(freshRetry.executionId()).isNotNull();

        List<PilotAttempt> attemptHistory = attempts.findByTaskIdOrderByOrdinalAsc(target.getId());
        assertThat(attemptHistory).hasSize(3);
        PilotAttempt initial = attemptHistory.get(0);
        assertThat(initial.getOrdinal()).isEqualTo(1);
        assertThat(initial.getState()).isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(initial.getResultReason()).isEqualTo("coding: verification failed");
        assertThat(initial.getExecutionId()).isNull();
        PilotAttempt withdrawn = attemptHistory.get(1);
        assertThat(withdrawn.getKind()).isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(withdrawn.getOrdinal()).isEqualTo(2);
        assertThat(withdrawn.getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
        assertThat(withdrawn.getResultReason()).isEqualTo("task left the pilot scope before start");
        assertThat(withdrawn.getExecutionId()).isNull();
        PilotAttempt fresh = attemptHistory.get(2);
        assertThat(fresh.getId()).isEqualTo(freshRetry.attemptId());
        assertThat(fresh.getKind()).isEqualTo(PilotAttempt.Kind.RETRY);
        assertThat(fresh.getOrdinal()).isEqualTo(3);
        assertThat(fresh.getState()).isEqualTo(PilotAttempt.State.QUEUED);
    }

    @Test
    void invalidRequestIdIsRejectedWithoutCreatingAttempts() {
        assertThatThrownBy(() -> retries.retry(new PilotCommands.Retry(" ", UUID.randomUUID(), 0, "input",
                "destination")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id is required");
        assertThatThrownBy(() -> retries.retry(new PilotCommands.Retry("r".repeat(64), UUID.randomUUID(), 0,
                "input", "destination")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 63 characters");

        assertThat(attempts.count()).isZero();
    }

    private String retryRejected(PilotTask task, String expectedReasonPart) {
        var click = retries.retry(new PilotCommands.Retry("retry-reject-" + task.getIssueId(), task.getId(),
                task.getVersion(), PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task)));
        assertThat(click.explanation()).contains(expectedReasonPart);
        assertThat(click.executionId()).isNull();
        return click.state();
    }

    private PilotTask directTask(String issueId, String displayKey) {
        return tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
    }

    private void stopWithFailedInitialAttempt(PilotTask task, String runId) {
        admit(task, runId);
        forceState(task, PilotAttempt.State.STOPPED, "coding: verification failed");
    }

    private void admit(PilotTask task, String runId) {
        var result = admissions.runSelected(new PilotCommands.RunSelected(runId, observed(),
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        assertThat(result.admittedTaskIds()).containsExactly(task.getId());
    }

    private void admitAndDispatch(PilotTask task, String runId) {
        admit(task, runId);
        assertThat(dispatcher.dispatchNext()).isPresent();
    }

    private void forceState(PilotTask task, PilotAttempt.State state, String reason) {
        PilotAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        attempt.setState(state);
        attempt.setResultReason(reason);
        attempts.save(attempt);
    }

    /**
     * Fixture stand-in for the outcome service's release: the active attempt
     * becomes terminal and the installation slot is freed, so the queue head
     * can be dispatched.
     */
    private void releaseSlot() {
        UUID activeId = installations.findById(1).orElseThrow().getActiveAttemptId();
        PilotAttempt active = attempts.findById(activeId).orElseThrow();
        active.setState(PilotAttempt.State.STOPPED);
        active.setResultReason("coding: verification failed");
        attempts.save(active);
        PilotInstallation installation = installations.findById(1).orElseThrow();
        installation.setActiveAttemptId(null);
        installations.save(installation);
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
