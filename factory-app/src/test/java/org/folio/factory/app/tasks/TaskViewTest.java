package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.app.tasks.presentation.TaskView;
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
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;

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
        "factory.tasks.execution.enabled=true",
        "factory.tasks.execution.deadline=PT30M",
        "factory.tasks.execution.dispatch-interval-ms=3600000",
        "factory.connectors.jira.base-url=https://jira.example.org"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TaskViewTest {

    private static final String INSTANCE = "jira.example.org";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskAdmissionService admissions;


    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    TaskOutcomeService outcomes;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    AuditLog auditLog;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    TaskQuery query;

    @BeforeEach
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
        // The migration seeds the single installation row; restore it as installed.
        doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void unstartedTaskProjectsSelectionWithEmptyEstimates() {
        Task task = eligibleTask("90001", "MODBAR-901");

        TaskView view = query.detail(task.getId());

        assertThat(view.taskId()).isEqualTo(task.getId());
        assertThat(view.state()).isEqualTo(TaskView.TaskState.AWAITING_SELECTION);
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
    void aWithdrawalAfterRealWorkShowsTheLastAttemptThatRan() {
        Task task = eligibleTask("90021", "MODBAR-921");
        admit(task, "run-921");
        forceState(task, TaskAttempt.State.STOPPED, "coding: verification failed");
        TaskAttempt withdrawn = new TaskAttempt(task.getId(), 2, TaskAttempt.Kind.RETRY, "legacy-921",
                TaskAttempt.State.WITHDRAWN);
        withdrawn.setResultReason("task left the My tasks scope before start");
        attempts.save(withdrawn);

        var view = query.detail(task.getId());

        assertThat(view.state()).isEqualTo(TaskView.TaskState.STOPPED);
        assertThat(view.reason()).isEqualTo("coding: verification failed");
        assertThat(view.allowedActions().retry()).isTrue();
        assertThat(view.attempts()).hasSize(2);
    }

    @Test
    void unsupportedWithdrawnAndUnassignedTasksKeepDistinctMeaningAndHistory() {
        Task unsupported = task("90011", "MODBAR-911", Task.Eligibility.INELIGIBLE,
                "depends on unsupported mod-data-import-converter-storage");
        Task withdrawn = eligibleTask("90012", "MODBAR-912");
        admit(withdrawn, "run-912");
        forceState(withdrawn, TaskAttempt.State.WITHDRAWN, "task left the My tasks scope before start");
        Task stopped = eligibleTask("90013", "MODBAR-913");
        admit(stopped, "run-913");
        forceState(stopped, TaskAttempt.State.STOPPED, "coding: verification failed");
        Task left = tasks.findById(stopped.getId()).orElseThrow();
        left.setInScope(false);
        tasks.save(left);

        var unsupportedView = query.detail(unsupported.getId());
        assertThat(unsupportedView.state()).isEqualTo(TaskView.TaskState.UNSUPPORTED);
        assertThat(unsupportedView.reason())
                .isEqualTo("depends on unsupported mod-data-import-converter-storage");
        assertThat(unsupportedView.destinationRepository()).isNull();
        assertThat(unsupportedView.allowedActions().select()).isFalse();
        assertThat(unsupportedView.allowedActions().retry()).isFalse();

        var withdrawnView = query.detail(withdrawn.getId());
        assertThat(withdrawnView.state()).isEqualTo(TaskView.TaskState.WITHDRAWN);
        assertThat(withdrawnView.reason()).isEqualTo("task left the My tasks scope before start");
        assertThat(withdrawnView.allowedActions().select()).isTrue();
        assertThat(withdrawnView.allowedActions().retry()).isFalse();

        var outOfScope = query.detail(stopped.getId());
        assertThat(outOfScope.inScope()).isFalse();
        assertThat(outOfScope.state()).isEqualTo(TaskView.TaskState.STOPPED);
        assertThat(outOfScope.reason()).isEqualTo("coding: verification failed");
        assertThat(outOfScope.stage()).isEqualTo("coding");
        assertThat(outOfScope.attempts()).hasSize(1);
        assertThat(outOfScope.attempts().getFirst().state()).isEqualTo("STOPPED");
        // Started work holds after the task leaves My tasks: it can still be retried.
        assertThat(outOfScope.allowedActions().select()).isFalse();
        assertThat(outOfScope.allowedActions().retry()).isTrue();

        var myTasks = query.myTasks(PageRequest.of(0, 10));
        assertThat(myTasks.available()).extracting(TaskView::displayKey)
                .containsExactlyInAnyOrder("MODBAR-911", "MODBAR-912");
        assertThat(myTasks.triggered().getContent()).extracting(TaskView::displayKey)
                .containsExactly("MODBAR-913");
    }

    @Test
    void engineCompletedWithFailedVerificationProjectsAsStoppedWithRetryAvailable() {
        Task failed = eligibleTask("90021", "MODBAR-921");
        admit(failed, "run-921");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, VerifyWorker.RESULT,
                "{\"state\":\"VERIFICATION_FAILED\",\"reason\":\"tests failed\"}");
        writeArtifact(executionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");
        outcomes.refresh(attempt.getId());

        TaskView view = query.detail(failed.getId());

        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(view.state()).isEqualTo(TaskView.TaskState.STOPPED);
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
        Task paused = eligibleTask("90031", "MODBAR-931");
        admit(paused, "run-931");
        UUID pausedExecution = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt pausedAttempt = attempts.findByExecutionId(pausedExecution).orElseThrow();
        stateManager.transition(pausedExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(pausedExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        outcomes.refresh(pausedAttempt.getId());

        TaskView pausedView = query.detail(paused.getId());
        assertThat(pausedView.state()).isEqualTo(TaskView.TaskState.PROCESSING_PAUSED);
        assertThat(pausedView.reason()).isNull();
        assertThat(pausedView.allowedActions().retry()).isFalse();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId())
                .isEqualTo(pausedAttempt.getId());

        doNothing().when(docker).cleanOwned(any(), anyString());
        assertThat(outcomes.refresh(pausedAttempt.getId()).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(query.detail(paused.getId()).state()).isEqualTo(TaskView.TaskState.STOPPED);

        Task ready = eligibleTask("90032", "MODBAR-932");
        admit(ready, "run-932");
        UUID readyExecution = dispatcher.dispatchNext().orElseThrow();
        writeArtifact(readyExecution, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/folio-org/mod-bar/pull/7\"}");
        outcomes.refresh(attempts.findByExecutionId(readyExecution).orElseThrow().getId());

        TaskView readyView = query.detail(ready.getId());
        assertThat(readyView.state()).isEqualTo(TaskView.TaskState.PR_READY);
        assertThat(readyView.pullRequestUrl()).isEqualTo("https://github.com/folio-org/mod-bar/pull/7");
        assertThat(readyView.attempts().getFirst().pullRequestUrl())
                .isEqualTo("https://github.com/folio-org/mod-bar/pull/7");
        assertThat(readyView.allowedActions().retry()).isFalse();
        assertThat(readyView.allowedActions().select()).isFalse();

        Task uncertain = eligibleTask("90033", "MODBAR-933");
        admit(uncertain, "run-933");
        forceState(uncertain, TaskAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");
        // An uncertain attempt projects distinctly because it does not own the
        // installation's pause slot; detach the admission's launch binding.
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setActiveAttemptId(null);
        installations.save(installation);

        TaskView uncertainView = query.detail(uncertain.getId());
        assertThat(uncertainView.state()).isEqualTo(TaskView.TaskState.UNCERTAIN);
        assertThat(uncertainView.stage()).isEqualTo("publish");
        assertThat(uncertainView.allowedActions().retry()).isFalse();
        assertThat(uncertainView.allowedActions().select()).isFalse();
    }

    @Test
    void queuedTaskProjectsWorkingWithoutControls() {
        Task working = eligibleTask("90041", "MODBAR-941");
        admit(working, "run-941");
        assertThat(dispatcher.dispatchNext()).isPresent();

        TaskView queued = query.detail(working.getId());
        assertThat(queued.state()).isEqualTo(TaskView.TaskState.WORKING);
        assertThat(queued.stage()).isNull();
        assertThat(queued.attempts().getFirst().state()).isEqualTo("QUEUED");
        assertThat(queued.attempts().getFirst().stage()).isNull();

        UUID executionId = attempts.findByTaskIdOrderByOrdinalAsc(working.getId())
                .getFirst().getExecutionId();
        stateManager.transition(executionId, ExecutionStatus.RUNNING, null);
        auditLog.record(executionId, AuditEventType.STEP_STARTED, "implement", Map.of());

        TaskView view = query.detail(working.getId());

        assertThat(view.state()).isEqualTo(TaskView.TaskState.WORKING);
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
        Task retried = eligibleTask("90051", "MODBAR-951");
        admit(retried, "run-951");
        UUID firstExecution = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt first = attempts.findByExecutionId(firstExecution).orElseThrow();
        usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1", -1, 100_000, 20_000, false);
        usageEvent(firstExecution, AuditEventType.STEP_COMPLETED, "a1-r1", 1, 60_000, 12_000, false);
        stateManager.transition(firstExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(firstExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        outcomes.refresh(first.getId());

        Task current = tasks.findById(retried.getId()).orElseThrow();
        var click = admissions.retry(new TaskCommands.Retry("retry-951", current.getId(), current.getVersion(),
                TaskAdmissionChecks.inputFingerprint(current),
                TaskAdmissionChecks.destinationFingerprint(current)));
        UUID secondExecution = click.executionId();
        assertThat(secondExecution).isNotNull();
        TaskAttempt second = attempts.findByExecutionId(secondExecution).orElseThrow();
        usageEvent(secondExecution, AuditEventType.RUNTIME_PROGRESS, "a2", -1, 100_000, 10_000, false);
        usageEvent(secondExecution, AuditEventType.RUNTIME_PROGRESS, "a2", -1, 150_000, 30_000, false);
        usageEvent(secondExecution, AuditEventType.STEP_COMPLETED, "a2", -1, 150_000, 30_000, true);
        stateManager.transition(secondExecution, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        writeArtifact(secondExecution, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        outcomes.refresh(second.getId());

        TaskView view = query.detail(retried.getId());

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
        assertThat(query.myTasks(PageRequest.of(0, 10)).triggered().getContent().stream()
                .filter(row -> row.taskId().equals(retried.getId()))
                .findFirst().orElseThrow()
                .usage().estimatesByCurrency().get("USD")).isEqualByComparingTo("1.24");
    }

    @Test
    void attemptActiveSecondsExcludeHumanReviewWaiting() {
        Task task = eligibleTask("90061", "MODBAR-961");
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
    void myTasksListsAvailableNewestFirstAndQueuedTasksInClickOrder() {
        Task older = withJiraCreated(eligibleTask("90071", "MODBAR-971"), "2026-10-01T09:00:00Z");
        Task newer = withJiraCreated(eligibleTask("90072", "MODBAR-972"), "2026-10-01T10:00:00Z");
        Task first = eligibleTask("90073", "MODBAR-973");
        Task second = eligibleTask("90074", "MODBAR-974");
        Task third = eligibleTask("90075", "MODBAR-975");
        admit(first, "run-973");
        admit(second, "run-974");
        admit(third, "run-975");
        dispatcher.dispatchNext().orElseThrow();

        var myTasks = query.myTasks(PageRequest.of(0, 10));

        assertThat(myTasks.available()).extracting(TaskView::displayKey)
                .containsExactly("MODBAR-972", "MODBAR-971");
        assertThat(myTasks.available()).allMatch(row -> row.allowedActions().select());
        assertThat(myTasks.triggered().getTotalElements()).isEqualTo(3);
        assertThat(query.detail(first.getId()).queuePosition()).isNull();
        assertThat(query.detail(second.getId()).queuePosition()).isEqualTo(1);
        assertThat(query.detail(third.getId()).queuePosition()).isEqualTo(2);
        assertThat(query.detail(older.getId()).queuePosition()).isNull();
        assertThat(query.myTasks(PageRequest.of(0, 1)).triggered().getContent()).hasSize(1);
    }

    @Test
    void malformedDisplayKeyYieldsNoJiraLink() {
        Task task = task("90081", "modbar-981/../../browse", Task.Eligibility.ELIGIBLE,
                "repository mod-bar");

        assertThat(query.detail(task.getId()).jiraUrl()).isNull();
    }

    private Task withJiraCreated(Task task, String createdAt) {
        Task stored = tasks.findById(task.getId()).orElseThrow();
        stored.setJiraCreatedAt(Instant.parse(createdAt));
        return tasks.save(stored);
    }

    private Task eligibleTask(String issueId, String displayKey) {
        return task(issueId, displayKey, Task.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    private Task task(String issueId, String displayKey, Task.Eligibility eligibility,
                           String routingReason) {
        return tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId, eligibility, routingReason));
    }

    private void admit(Task task, String runId) {
        var result = admissions.runSelected(new TaskCommands.RunSelected(runId,
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).isEmpty();
    }

    private void forceState(Task task, TaskAttempt.State state, String reason) {
        TaskAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        attempt.setState(state);
        attempt.setResultReason(reason);
        attempts.save(attempt);
    }

    private void writeArtifact(UUID executionId, String name, String content) {
        artifactStore.putMarkdown(executionId, name, content, "task-view-test");
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
        auditLog.record(executionId, type, "implement", "task-view-test", detail);
    }

    private void timedEvent(UUID executionId, AuditEventType type, String stepId, Instant when) {
        var event = new AuditEvent(executionId, type, stepId, "task-view-test", "{}");
        ReflectionTestUtils.setField(event, "occurredAt", when);
        auditEvents.save(event);
    }
}
