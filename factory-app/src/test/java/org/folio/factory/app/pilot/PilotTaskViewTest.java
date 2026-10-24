package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.startup.PilotReadinessGate;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.folio.factory.app.web.AttemptCharge;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Logical-task projection against real PostgreSQL: every distinct outcome has
 * its own task state, engine COMPLETED with failed verification reads as
 * stopped from the attempt/result state, history survives leaving the Jira
 * filter, task totals sum each attempt contribution exactly once, and attempt
 * durations exclude human-review waiting.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.deadline=PT30M",
        "factory.connectors.jira.base-url=https://jira.example.org"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotTaskViewTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String AUTHORITY = "authority-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    /** The projection tests own their attempt state; startup readiness is covered by PilotStartupTest. */
    @MockitoBean
    PilotReadinessGate readiness;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotRetryService retries;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotOutcomeService outcomes;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    PilotTaskQuery query;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void unstartedTaskProjectsSelectionWithEmptyEstimates() {
        PilotTask task = eligibleTask("90001", "MODBAR-901");

        PilotTaskView view = query.detail(task.getId());

        assertThat(view.taskId()).isEqualTo(task.getId());
        assertThat(view.state()).isEqualTo(PilotTaskView.TaskState.AWAITING_SELECTION);
        assertThat(view.jiraUrl()).isEqualTo("https://jira.example.org/browse/MODBAR-901");
        assertThat(view.destinationRepository()).isEqualTo("mod-bar");
        assertThat(view.reason()).isNull();
        assertThat(view.attempts()).isEmpty();
        assertThat(view.allowedActions().select()).isTrue();
        assertThat(view.allowedActions().retry()).isFalse();
        var unstartedUsage = view.usage();
        assertThat(unstartedUsage.estimatesByCurrency()).isEmpty();
        assertThat(unstartedUsage.tokens()).isZero();
        assertThat(unstartedUsage.complete()).isTrue();
    }

    @Test
    void unsupportedWithdrawnAndOutOfScopeTasksKeepDistinctMeaningAndHistory() {
        PilotTask unsupported = task("90011", "MODBAR-911", PilotTask.Eligibility.INELIGIBLE,
                "depends on unsupported mod-data-import-converter-storage");
        PilotTask withdrawn = eligibleTask("90012", "MODBAR-912");
        admit(withdrawn, "run-912");
        forceState(withdrawn, PilotAttempt.State.WITHDRAWN, "task left the pilot scope before start");
        PilotTask stopped = eligibleTask("90013", "MODBAR-913");
        admit(stopped, "run-913");
        forceState(stopped, PilotAttempt.State.STOPPED, "coding: verification failed");
        PilotTask left = tasks.findById(stopped.getId()).orElseThrow();
        left.setInScope(false);
        tasks.save(left);

        var unsupportedView = query.detail(unsupported.getId());
        assertThat(unsupportedView.state()).isEqualTo(PilotTaskView.TaskState.UNSUPPORTED);
        assertThat(unsupportedView.reason())
                .isEqualTo("depends on unsupported mod-data-import-converter-storage");
        assertThat(unsupportedView.destinationRepository()).isNull();
        assertThat(unsupportedView.allowedActions().select()).isFalse();
        assertThat(unsupportedView.allowedActions().retry()).isFalse();

        var withdrawnView = query.detail(withdrawn.getId());
        assertThat(withdrawnView.state()).isEqualTo(PilotTaskView.TaskState.WITHDRAWN);
        assertThat(withdrawnView.reason()).isEqualTo("task left the pilot scope before start");
        assertThat(withdrawnView.allowedActions().select()).isTrue();
        assertThat(withdrawnView.allowedActions().retry()).isFalse();

        var outOfScope = query.detail(stopped.getId());
        assertThat(outOfScope.inScope()).isFalse();
        assertThat(outOfScope.state()).isEqualTo(PilotTaskView.TaskState.STOPPED);
        assertThat(outOfScope.reason()).isEqualTo("coding: verification failed");
        assertThat(outOfScope.stage()).isEqualTo("coding");
        assertThat(outOfScope.attempts()).hasSize(1);
        assertThat(outOfScope.attempts().getFirst().state()).isEqualTo("STOPPED");
        assertThat(outOfScope.allowedActions().select()).isFalse();
        assertThat(outOfScope.allowedActions().retry()).isFalse();

        assertThat(query.list(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(3);
    }

    @Test
    void engineCompletedWithFailedVerificationProjectsAsStoppedWithRetryAvailable() {
        PilotTask failed = eligibleTask("90021", "MODBAR-921");
        admit(failed, "run-921");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, VerifyWorker.RESULT,
                "{\"state\":\"VERIFICATION_FAILED\",\"reason\":\"tests failed\"}");
        writeArtifact(executionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");
        outcomes.refresh(attempt.getId());

        PilotTaskView view = query.detail(failed.getId());

        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(view.state()).isEqualTo(PilotTaskView.TaskState.STOPPED);
        assertThat(view.reason()).isEqualTo("VERIFICATION_FAILED: tests failed");
        assertThat(view.stage()).isEqualTo("VERIFICATION_FAILED");
        var entry = view.attempts().getFirst();
        assertThat(entry.state()).isEqualTo("STOPPED");
        assertThat(entry.executionStatus()).isEqualTo("COMPLETED");
        assertThat(entry.executionId()).isEqualTo(executionId);
        assertThat(view.allowedActions().retry()).isTrue();
        assertThat(view.allowedActions().select()).isFalse();
    }

    @Test
    void uncertainPausedAndPrReadyStatesStayDistinct() {
        PilotTask paused = eligibleTask("90031", "MODBAR-931");
        admit(paused, "run-931");
        UUID pausedExecution = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt pausedAttempt = attempts.findByExecutionId(pausedExecution).orElseThrow();
        stateManager.transition(pausedExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(pausedExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        outcomes.refresh(pausedAttempt.getId());

        PilotTaskView pausedView = query.detail(paused.getId());
        assertThat(pausedView.state()).isEqualTo(PilotTaskView.TaskState.PROCESSING_PAUSED);
        assertThat(pausedView.reason()).isNull();
        assertThat(pausedView.allowedActions().retry()).isFalse();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId())
                .isEqualTo(pausedAttempt.getId());

        doNothing().when(docker).cleanOwned(any(), anyString());
        assertThat(outcomes.refresh(pausedAttempt.getId()).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(query.detail(paused.getId()).state()).isEqualTo(PilotTaskView.TaskState.STOPPED);

        PilotTask ready = eligibleTask("90032", "MODBAR-932");
        admit(ready, "run-932");
        UUID readyExecution = dispatcher.dispatchNext().orElseThrow();
        writeArtifact(readyExecution, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/folio-org/mod-bar/pull/7\"}");
        outcomes.refresh(attempts.findByExecutionId(readyExecution).orElseThrow().getId());

        PilotTaskView readyView = query.detail(ready.getId());
        assertThat(readyView.state()).isEqualTo(PilotTaskView.TaskState.PR_READY);
        assertThat(readyView.pullRequestUrl()).isEqualTo("https://github.com/folio-org/mod-bar/pull/7");
        assertThat(readyView.attempts().getFirst().pullRequestUrl())
                .isEqualTo("https://github.com/folio-org/mod-bar/pull/7");
        assertThat(readyView.allowedActions().retry()).isFalse();
        assertThat(readyView.allowedActions().select()).isFalse();

        PilotTask uncertain = eligibleTask("90033", "MODBAR-933");
        admit(uncertain, "run-933");
        forceState(uncertain, PilotAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");

        PilotTaskView uncertainView = query.detail(uncertain.getId());
        assertThat(uncertainView.state()).isEqualTo(PilotTaskView.TaskState.UNCERTAIN);
        assertThat(uncertainView.stage()).isEqualTo("publish");
        assertThat(uncertainView.allowedActions().retry()).isFalse();
        assertThat(uncertainView.allowedActions().select()).isFalse();
    }

    @Test
    void queuedTaskProjectsWorkingWithoutControls() {
        PilotTask working = eligibleTask("90041", "MODBAR-941");
        admit(working, "run-941");
        assertThat(dispatcher.dispatchNext()).isPresent();

        PilotTaskView queued = query.detail(working.getId());
        assertThat(queued.state()).isEqualTo(PilotTaskView.TaskState.WORKING);
        assertThat(queued.stage()).isNull();
        assertThat(queued.attempts().getFirst().state()).isEqualTo("QUEUED");
        assertThat(queued.attempts().getFirst().stage()).isNull();

        UUID executionId = attempts.findByTaskIdOrderByOrdinalAsc(working.getId())
                .getFirst().getExecutionId();
        stateManager.transition(executionId, ExecutionStatus.RUNNING, null);
        auditLog.record(executionId, AuditEventType.STEP_STARTED, "implement", Map.of());

        PilotTaskView view = query.detail(working.getId());

        assertThat(view.state()).isEqualTo(PilotTaskView.TaskState.WORKING);
        assertThat(view.stage()).isEqualTo("implement");
        var entry = view.attempts().getFirst();
        assertThat(entry.stage()).isEqualTo("implement");
        assertThat(entry.state()).isEqualTo("QUEUED");
        assertThat(entry.kind()).isEqualTo("INITIAL");
        assertThat(entry.startedAt()).isNotNull();
        assertThat(entry.deadlineAt()).isAfter(entry.startedAt());
        assertThat(view.allowedActions().select()).isFalse();
        assertThat(view.allowedActions().retry()).isFalse();
    }

    @Test
    void taskTotalsSumInitialFeedbackAndRetryContributionsExactlyOnce() {
        PilotTask retried = eligibleTask("90051", "MODBAR-951");
        admit(retried, "run-951");
        UUID firstExecution = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt first = attempts.findByExecutionId(firstExecution).orElseThrow();
        usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1", -1, 100_000, 20_000, false);
        usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1-r1", 1, 60_000, 12_000, false);
        stateManager.transition(firstExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(firstExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        outcomes.refresh(first.getId());

        PilotTask current = tasks.findById(retried.getId()).orElseThrow();
        var click = retries.retry(new PilotCommands.Retry("retry-951", current.getId(), current.getVersion(),
                PilotAdmissionService.inputFingerprint(current),
                PilotAdmissionService.destinationFingerprint(current)));
        UUID secondExecution = click.executionId();
        assertThat(secondExecution).isNotNull();
        PilotAttempt second = attempts.findByExecutionId(secondExecution).orElseThrow();
        usageEvent(secondExecution, AuditEventType.RUNTIME_PROGRESS, "a2", -1, 100_000, 10_000, false);
        usageEvent(secondExecution, AuditEventType.RUNTIME_PROGRESS, "a2", -1, 150_000, 30_000, false);
        usageEvent(secondExecution, AuditEventType.STEP_COMPLETED, "a2", -1, 150_000, 30_000, true);
        stateManager.transition(secondExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(secondExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        outcomes.refresh(second.getId());

        PilotTaskView view = query.detail(retried.getId());

        var taskUsage = view.usage();
        assertThat(taskUsage.estimatesByCurrency().get("USD")).isEqualByComparingTo("1.24");
        assertThat(taskUsage.complete()).isFalse();
        assertThat(taskUsage.tokens()).isEqualTo(372_000L);
        assertThat(taskUsage.tokensByCategory())
                .containsEntry(AttemptCharge.Category.INPUT, 310_000L)
                .containsEntry(AttemptCharge.Category.OUTPUT, 62_000L);
        assertThat(view.attempts()).hasSize(2);
        assertThat(view.attempts().get(0).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.64");
        assertThat(view.attempts().get(0).usage().complete()).isTrue();
        assertThat(view.attempts().get(1).usage().estimatesByCurrency().get("USD"))
                .isEqualByComparingTo("0.60");
        assertThat(view.attempts().get(1).usage().complete()).isFalse();
        assertThat(view.attempts().get(1).kind()).isEqualTo("RETRY");
        assertThat(query.list(PageRequest.of(0, 10)).getContent().stream()
                .filter(row -> row.taskId().equals(retried.getId()))
                .findFirst().orElseThrow()
                .usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("1.24");
    }

    @Test
    void attemptActiveSecondsExcludeHumanReviewWaiting() {
        PilotTask task = eligibleTask("90061", "MODBAR-961");
        admit(task, "run-961");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        Instant base = Instant.parse("2026-10-23T10:00:00Z");
        timedEvent(executionId, AuditEventType.STEP_STARTED, "implement", base);
        timedEvent(executionId, AuditEventType.HITL_REQUESTED, "implement", base.plusSeconds(10));
        timedEvent(executionId, AuditEventType.STEP_STARTED, "implement", base.plusSeconds(100));
        timedEvent(executionId, AuditEventType.STEP_COMPLETED, "implement", base.plusSeconds(130));

        var entry = query.detail(task.getId()).attempts().getFirst();

        assertThat(entry.activeSeconds()).isEqualTo(40);
    }

    @Test
    void listPagesTasksInQueueOrderAndExposesAutomaticAdmissionAvailability() {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setObservedAuthorityFingerprint(AUTHORITY);
        installation.setSyncComplete(true);
        installations.save(installation);
        PilotTask older = eligibleTask("90071", "MODBAR-971");
        PilotTask savedOlder = tasks.findById(older.getId()).orElseThrow();
        savedOlder.setJiraCreatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        tasks.save(savedOlder);
        PilotTask newer = eligibleTask("90072", "MODBAR-972");
        PilotTask savedNewer = tasks.findById(newer.getId()).orElseThrow();
        savedNewer.setJiraCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
        tasks.save(savedNewer);

        Page<PilotTaskView> page = query.list(PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().displayKey()).isEqualTo("MODBAR-971");
        assertThat(page.getContent().getFirst().allowedActions().enableAutomaticAdmission()).isTrue();
        assertThat(query.detail(newer.getId()).allowedActions().enableAutomaticAdmission()).isTrue();

        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);
        assertThat(query.detail(newer.getId()).allowedActions().enableAutomaticAdmission()).isFalse();
    }

    @Test
    void malformedDisplayKeyYieldsNoJiraLink() {
        PilotTask task = task("90081", "modbar-981/../../browse", PilotTask.Eligibility.ELIGIBLE,
                "repository mod-bar");

        assertThat(query.detail(task.getId()).jiraUrl()).isNull();
    }

    private PilotTask eligibleTask(String issueId, String displayKey) {
        return task(issueId, displayKey, PilotTask.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    private PilotTask task(String issueId, String displayKey, PilotTask.Eligibility eligibility,
                           String routingReason) {
        return tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId, eligibility, routingReason));
    }

    private void admit(PilotTask task, String runId) {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setAcceptedAuthorityFingerprint(AUTHORITY);
        installation.setObservedAuthorityFingerprint(AUTHORITY);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        var result = admissions.runSelected(new PilotCommands.RunSelected(runId, AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).isEmpty();
    }

    private void forceState(PilotTask task, PilotAttempt.State state, String reason) {
        PilotAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        attempt.setState(state);
        attempt.setResultReason(reason);
        attempts.save(attempt);
    }

    private void writeArtifact(UUID executionId, String name, String content) {
        artifactStore.putMarkdown(executionId, name, content, "pilot-task-view-test");
    }

    private void usageEvent(UUID executionId, AuditEventType type, String attemptId, int round,
                            long input, long output, boolean incomplete) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("attemptId", attemptId);
        detail.put("round", round);
        detail.put("model", "claude-sonnet-5-5");
        if (type == AuditEventType.RUNTIME_PROGRESS) {
            detail.put("activity", "coding_usage");
        }
        detail.put("inputTokens", input);
        detail.put("outputTokens", output);
        detail.put("cacheReadTokens", 0);
        detail.put("cacheWriteTokens", 0);
        detail.put("rate", Map.of("currency", "USD", "inputPerMillion", 2,
                "cacheReadPerMillion", 0.1, "outputPerMillion", 10));
        if (incomplete) {
            detail.put("usageIncomplete", true);
        }
        auditLog.record(executionId, type, "implement", "pilot-task-view-test", detail);
    }

    private void timedEvent(UUID executionId, AuditEventType type, String stepId, Instant when) {
        var event = new AuditEvent(executionId, type, stepId, "pilot-task-view-test", "{}");
        ReflectionTestUtils.setField(event, "occurredAt", when);
        auditEvents.save(event);
    }
}
