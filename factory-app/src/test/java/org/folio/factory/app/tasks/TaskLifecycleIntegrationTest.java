package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.TaskDispatchPoller;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;

/**
 * Whole-attempt bounds, ownership and queue progress against real PostgreSQL:
 * the deadline is persisted once when preparation starts and never reset, a
 * terminal outcome releases the active slot only with proven workload
 * inactivity — a proven shared failure stops with its persisted cause and lets
 * the queue continue, an unproven one holds — and a delivered pull request
 * releases the slot before feedback or merge. The dispatch poller advances the
 * queue after a release. The engine is disabled, so execution states and
 * artifacts are driven directly.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.tasks.execution.enabled=true",
        "factory.tasks.execution.deadline=PT30M",
        "factory.tasks.execution.dispatch-interval-ms=3600000"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class TaskLifecycleIntegrationTest {

    private static final String INSTANCE = "jira.example.org";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    ArtifactStore artifactStore;

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
    TaskDispatchPoller dispatchPoller;

    @Autowired
    TaskAttemptPolicy taskPolicy;

    @BeforeEach
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void deadlineIsPersistedOnceWhenPreparationStartsAndNeverReset() {
        admit("50001", "MODBAR-501");

        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();

        assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(attempt.getStartedAt()).isNotNull();
        assertThat(attempt.getDeadlineAt())
                .isCloseTo(attempt.getStartedAt().plus(Duration.ofMinutes(30)),
                        org.assertj.core.api.Assertions.within(2, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(attempt.getDeadlineAt()).isAfter(attempt.getStartedAt());
        Instant persistedDeadline = attempt.getDeadlineAt();

        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getDeadlineAt())
                .isEqualTo(persistedDeadline);

        TaskAttempt rewound = attempts.findById(attempt.getId()).orElseThrow();
        rewound.setStartedAt(Instant.now().minusSeconds(7200));
        rewound.setDeadlineAt(Instant.now().minusSeconds(5400));
        attempts.save(rewound);

        dispatcher.dispatchNext();

        TaskAttempt afterRestart = attempts.findById(attempt.getId()).orElseThrow();
        assertThat(afterRestart.getDeadlineAt()).isEqualTo(rewound.getDeadlineAt());
        assertThat(afterRestart.getStartedAt()).isEqualTo(rewound.getStartedAt());
    }

    @Test
    void taskSpecificFailureReleasesTheQueueOnlyOnceWorkloadsAreProvenInactive(CapturedOutput output) {
        admit("50011", "MODBAR-511");
        admit("50012", "MODBAR-512");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");

        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        TaskOutcomeService.AttemptOutcome uncertain = outcomes.refresh(attempt.getId());

        assertThat(uncertain.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(uncertain.uncertainty()).contains("docker ps failed");
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(output).contains("Reconciled active task attempt " + attempt.getId()
                + " to NEEDS_INSPECTION: workload inactivity could not be confirmed: docker ps failed");
        long executionsBeforeNext = executions.count();

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        TaskOutcomeService.AttemptOutcome resolved = outcomes.refresh(attempt.getId());

        assertThat(resolved.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(resolved.reason()).contains("DEVELOPMENT_FAILED: syntax");
        assertThat(resolved.released()).isTrue();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(nextExecutionId).isNotEqualTo(executionId);
        assertThat(executions.count()).isEqualTo(executionsBeforeNext + 1);
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.QUEUED);
    }

    @Test
    void failedEscalatedAloneIsNotSufficientProofOfASafeTerminalStop() {
        admit("50021", "MODBAR-521");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                java.util.Map.of("error", "step exhausted retries"));
        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());

        TaskOutcomeService.AttemptOutcome uncertain = outcomes.refresh(attempt.getId());

        assertThat(uncertain.state())
                .as("the escalated failure alone resolves to nothing without workload evidence")
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        TaskOutcomeService.AttemptOutcome resolved = outcomes.refresh(attempt.getId());

        assertThat(resolved.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void provenSharedFailureStopsTheAttemptWithItsCauseAndReleasesTheQueue() {
        admit("50031", "MODBAR-531");
        admit("50032", "MODBAR-532");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, DevelopWorker.OUTCOME,
                "{\"status\":\"FAILED\",\"failure\":{\"code\":\"PROVIDER_FAILED\",\"message\":\"provider unreachable\"}}");
        writeArtifact(executionId, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"provider unreachable\"}");

        TaskOutcomeService.AttemptOutcome outcome = outcomes.refresh(attempt.getId());

        assertThat(outcome.state())
                .as("with proven inactivity a shared outage is a readable stop, not a wedged hold")
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(outcome.released()).isTrue();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getResultReason())
                .as("the shared cause is persisted on the attempt, not only in artifacts")
                .isNotBlank()
                .contains("shared:", "DEVELOPMENT_FAILED: provider unreachable");
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(nextExecutionId).isNotEqualTo(executionId);
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.QUEUED);
    }

    @Test
    void unprovenSharedFailureStillHoldsUntilInactivityIsProvenThenTheQueueContinues() {
        admit("50033", "MODBAR-533");
        admit("50034", "MODBAR-534");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, DevelopWorker.OUTCOME,
                "{\"status\":\"FAILED\",\"failure\":{\"code\":\"PROVIDER_FAILED\",\"message\":\"provider unreachable\"}}");
        writeArtifact(executionId, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"provider unreachable\"}");
        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());

        TaskOutcomeService.AttemptOutcome held = outcomes.refresh(attempt.getId());

        assertThat(held.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(attempts.findDispatchable(TaskAttempt.State.RESERVED,
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .as("the next task stays queued while inactivity is unproven")
                .hasSize(1);

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        TaskOutcomeService.AttemptOutcome resolved = outcomes.refresh(attempt.getId());

        assertThat(resolved.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getResultReason())
                .contains("shared:", "provider unreachable");
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.QUEUED);
    }

    @Test
    void dispatchPollerLaunchesTheNextQueuedAttemptAfterATerminalOutcome() {
        admit("50035", "MODBAR-535");
        UUID secondTaskId = admit("50036", "MODBAR-536");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(attempt.getId()).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(attempts.findLiveByTaskId(secondTaskId).getFirst().getState())
                .isEqualTo(TaskAttempt.State.RESERVED);
        long executionsBefore = executions.count();

        dispatchPoller.poll();

        TaskAttempt launched = attempts.findLiveByTaskId(secondTaskId).getFirst();
        assertThat(launched.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(launched.getExecutionId()).isNotNull();
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(launched.getId());
    }

    @Test
    void engineCompletedAloneIsNotPrReadyButADeliveredPullRequestReleasesBeforeFeedback() {
        admit("50041", "MODBAR-541");
        admit("50042", "MODBAR-542");
        UUID failedExecutionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt failedAttempt = attempts.findByExecutionId(failedExecutionId).orElseThrow();
        stateManager.transition(failedExecutionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(failedExecutionId, VerifyWorker.RESULT,
                "{\"state\":\"VERIFICATION_FAILED\",\"reason\":\"tests failed\"}");
        writeArtifact(failedExecutionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");

        TaskOutcomeService.AttemptOutcome stopped = outcomes.refresh(failedAttempt.getId());

        assertThat(stopped.state())
                .as("engine COMPLETED with failed verification is a stopped result, not PR_READY")
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(attempts.findById(failedAttempt.getId()).orElseThrow().getPublicationIdentity()).isNull();

        UUID deliveredExecutionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt deliveredAttempt = attempts.findByExecutionId(deliveredExecutionId).orElseThrow();
        writeArtifact(deliveredExecutionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/7\","
                        + "\"deliveryBranch\":\"factory/modbar-542\"}");

        TaskOutcomeService.AttemptOutcome ready = outcomes.refresh(deliveredAttempt.getId());

        assertThat(ready.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.PR_READY);
        assertThat(attempts.findById(deliveredAttempt.getId()).orElseThrow().getPublicationIdentity())
                .isEqualTo("https://github.com/user/repo/pull/7");
        assertThat(attempts.findById(deliveredAttempt.getId()).orElseThrow().getDraftReasons()).isNull();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId())
                .as("the slot is released while the execution is still running its feedback steps")
                .isNull();
        assertThat(stateManager.get(deliveredExecutionId).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void aDraftPullRequestIsPrReadyAndKeepsItsDraftReasonsOnTheAttempt() {
        admit("50045", "MODBAR-545");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        writeArtifact(executionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/8\","
                        + "\"pullRequestDraft\":true,\"draftReasons\":[\"1 acceptance criterion is not met.\"]}");

        TaskOutcomeService.AttemptOutcome ready = outcomes.refresh(attempt.getId());

        assertThat(ready.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.PR_READY);
        TaskAttempt stored = attempts.findById(attempt.getId()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(TaskAttempt.State.PR_READY);
        assertThat(stored.getDraftReasons()).contains("1 acceptance criterion is not met.");
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void staleAttemptsCannotPublishInterfereWithOrMarkANewerAttemptComplete() {
        admit("50051", "MODBAR-551");
        admit("50052", "MODBAR-552");
        UUID firstExecutionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt first = attempts.findByExecutionId(firstExecutionId).orElseThrow();
        failExecution(firstExecutionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(first.getId()).state())
                .isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);

        UUID secondExecutionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt second = attempts.findByExecutionId(secondExecutionId).orElseThrow();

        assertThat(taskPolicy.ownsActiveSlot(firstExecutionId)).isFalse();
        assertThat(taskPolicy.ownsActiveSlot(secondExecutionId)).isTrue();

        taskPolicy.recordStop(firstExecutionId, "publish", "a stale worker tried to publish");
        taskPolicy.recordStop(secondExecutionId, "publish", "a stale worker touched the newer attempt");

        assertThat(attempts.findById(first.getId()).orElseThrow().getResultReason())
                .doesNotContain("stale worker touched");
        assertThat(attempts.findById(second.getId()).orElseThrow().getResultReason())
                .contains("stale worker touched");

        TaskOutcomeService.AttemptOutcome replay = outcomes.refresh(first.getId());

        assertThat(replay.state()).isEqualTo(TaskOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(second.getId());
    }

    @Test
    void startupDispatchResolvesTheOneActiveAttempt() {
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        dispatchPoller.onStartup();

        admit("50061", "MODBAR-561");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        TaskAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");

        dispatchPoller.onStartup();

        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(dispatcher.dispatchNext()).isEmpty();
    }

    private void failExecution(UUID executionId, String resultJson) {
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                java.util.Map.of("error", "step exhausted retries"));
        writeArtifact(executionId, VerifyWorker.RESULT, resultJson);
    }

    private void writeArtifact(UUID executionId, String name, String content) {
        artifactStore.putMarkdown(executionId, name, content, "task-lifecycle-test");
    }

    private UUID admit(String issueId, String displayKey) {
        Task task = tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar"));
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        var result = admissions.runSelected(new TaskCommands.RunSelected("run-" + issueId,
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionChecks.inputFingerprint(task),
                        TaskAdmissionChecks.destinationFingerprint(task))), null));
        org.assertj.core.api.Assertions.assertThat(result.rejectedReasons()).isEmpty();
        return task.getId();
    }

}
