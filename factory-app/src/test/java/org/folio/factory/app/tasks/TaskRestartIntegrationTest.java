package org.folio.factory.app.tasks;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.PersistedTaskAttemptPolicy;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.execution.TaskPublicationGuard;
import org.folio.factory.app.tasks.execution.TaskDispatchPoller;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskPublicationIntentRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.folio.factory.core.domain.ExecutionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;

/**
 * Crash/restart semantics for in-flight task work against real PostgreSQL: a
 * freshly constructed recovery service and attempt policy — the state a restart
 * starts from — reclaim the running attempt without a new paid start, without
 * resetting the persisted deadline and only with proven workload inactivity;
 * and a restart never duplicates attempt rows or executions for an admitted task.
 */
@FactoryIntegrationTest
@ActiveProfiles("task-execution")
@TestPropertySource(properties = {
        "factory.limits.dedup.window=PT0S",
        "factory.tasks.source.assignee=Jane Developer",
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR"})
// Own context: task queue with its own Jira, assignee and mod-bar catalog; closing it stops its schedulers and frees its connections.
@DirtiesContext
class TaskRestartIntegrationTest {

    private static final String INSTANCE = "jira.example.org";

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
    TaskPublicationIntentRepository intents;

    @Autowired
    TaskAdmissionService admissions;

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    TaskOutcomeService outcomes;

    @Autowired
    TaskAttemptPolicy livePolicy;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void restartedRecoveryReclaimsTheRunningAttemptWithoutANewPaidStartOrDeadlineReset() {
        syncReady();
        long attemptsBefore = attempts.count();
        long executionsBefore = executions.count();
        UUID firstTask = admit("90001", "MODBAR-901");
        admit("90002", "MODBAR-902");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        UUID attemptId = attempts.findByExecutionId(executionId).orElseThrow().getId();
        stateManager.transition(executionId, ExecutionStatus.RUNNING, Map.of());
        assertThat(livePolicy.claimPaidStart(executionId)).isTrue();
        Instant startedAt = attempts.findById(attemptId).orElseThrow().getStartedAt();
        Instant deadlineAt = attempts.findById(attemptId).orElseThrow().getDeadlineAt();
        assertThat(deadlineAt).isEqualTo(startedAt.plus(Duration.ofMinutes(30)));

        new TaskDispatchPoller(dispatcher).onStartup();
        PersistedTaskAttemptPolicy restartedPolicy = restartedPolicy();

        assertThat(restartedPolicy.claimPaidStart(executionId))
                .as("a restart replays no paid start for the claimed attempt")
                .isFalse();
        assertThat(restartedPolicy.ownsActiveSlot(executionId)).isTrue();
        assertThat(restartedPolicy.find(executionId)).isPresent();
        TaskAttempt reclaimed = attempts.findById(attemptId).orElseThrow();
        assertThat(reclaimed.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(reclaimed.getPaidStartAt()).isNotNull();
        assertThat(reclaimed.getStartedAt()).isEqualTo(startedAt);
        assertThat(reclaimed.getDeadlineAt()).isEqualTo(deadlineAt);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attemptId);
        assertThat(outcomes.refresh(attemptId).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.PROCESSING);
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 2);

        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        outcomes.refresh(attemptId);
        assertThat(attempts.findById(attemptId).orElseThrow().getState())
                .as("a live execution stays queued while the outage only blocks resolution")
                .isEqualTo(TaskAttempt.State.QUEUED);

        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(attemptId).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(attempts.findById(attemptId).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attemptId);
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        outcomes.refresh(attemptId);
        TaskAttempt resolved = attempts.findById(attemptId).orElseThrow();
        assertThat(resolved.getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(resolved.getStartedAt()).isEqualTo(startedAt);
        assertThat(resolved.getDeadlineAt()).isEqualTo(deadlineAt);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getTaskId())
                .isNotEqualTo(firstTask);
        assertThat(executions.count()).isEqualTo(executionsBefore + 2);
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 2);
    }

    @Test
    void restartResolvesAdmissionAndDispatchIdempotentlyWithoutDuplicateRows() {
        syncReady();
        long attemptsBefore = attempts.count();
        long executionsBefore = executions.count();
        UUID taskId = admit("90021", "MODBAR-921");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 1);

        new TaskDispatchPoller(dispatcher).onStartup();
        Task task = tasks.findById(taskId).orElseThrow();

        var repeat = admissions.runSelected(new TaskCommands.RunSelected("run-90021",
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task))), null));
        assertThat(repeat.admittedTaskIds()).containsExactly(taskId);
        assertThat(attempts.count()).isEqualTo(1);

        var conflicting = admissions.runSelected(new TaskCommands.RunSelected("run-90021-late",
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task))), null));
        assertThat(conflicting.admittedTaskIds()).isEmpty();
        assertThat(conflicting.rejectedReasons().get(taskId)).contains("already has a live admission");
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 1);

        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);
        assertThat(attempts.findByExecutionId(executionId).orElseThrow().getExecutionId())
                .isEqualTo(executionId);
    }

    private PersistedTaskAttemptPolicy restartedPolicy() {
        return new PersistedTaskAttemptPolicy(attempts, tasks, installations, intents,
                new TaskPublicationGuard(attempts, tasks), transactionManager);
    }

    private void failExecution(UUID executionId, String resultJson) {
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        artifactStore.putMarkdown(executionId, VerifyWorker.RESULT, resultJson, "task-restart-test");
    }

    private void syncReady() {
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
    }

    private UUID admit(String issueId, String displayKey) {
        Task task = tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar"));
        syncReady();
        var result = admissions.runSelected(new TaskCommands.RunSelected("run-" + issueId,
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task))), null));
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        return task.getId();
    }
}
