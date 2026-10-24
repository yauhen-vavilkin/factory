package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.execution.PilotRecoveryService;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.startup.PilotReadinessGate;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Whole-attempt bounds, ownership and queue progress against real PostgreSQL:
 * the deadline is persisted once when preparation starts and never reset, a
 * terminal outcome releases the active slot only with proven workload
 * inactivity, a shared failure holds one installation pause, and a delivered
 * pull request releases the slot before feedback or merge. The engine is
 * disabled, so execution states and artifacts are driven directly.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.deadline=PT30M"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotLifecycleIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String AUTHORITY = "authority-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    /** The lifecycle tests own their attempt state; startup readiness is covered by PilotStartupTest. */
    @MockitoBean
    PilotReadinessGate readiness;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotOutcomeService outcomes;

    @Autowired
    PilotRecoveryService recovery;

    @Autowired
    PilotAttemptPolicy pilotPolicy;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void deadlineIsPersistedOnceWhenPreparationStartsAndNeverReset() {
        admit("50001", "MODBAR-501");

        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();

        assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.QUEUED);
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

        PilotAttempt rewound = attempts.findById(attempt.getId()).orElseThrow();
        rewound.setStartedAt(Instant.now().minusSeconds(7200));
        rewound.setDeadlineAt(Instant.now().minusSeconds(5400));
        attempts.save(rewound);

        dispatcher.dispatchNext();

        PilotAttempt afterRestart = attempts.findById(attempt.getId()).orElseThrow();
        assertThat(afterRestart.getDeadlineAt()).isEqualTo(rewound.getDeadlineAt());
        assertThat(afterRestart.getStartedAt()).isEqualTo(rewound.getStartedAt());
    }

    @Test
    void taskSpecificFailureReleasesTheQueueOnlyOnceWorkloadsAreProvenInactive() {
        admit("50011", "MODBAR-511");
        admit("50012", "MODBAR-512");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");

        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        PilotOutcomeService.AttemptOutcome uncertain = outcomes.refresh(attempt.getId());

        assertThat(uncertain.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(uncertain.uncertainty()).contains("docker ps failed");
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        long executionsBeforeNext = executions.count();

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        PilotOutcomeService.AttemptOutcome resolved = outcomes.refresh(attempt.getId());

        assertThat(resolved.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(resolved.reason()).contains("DEVELOPMENT_FAILED: syntax");
        assertThat(resolved.workloadInactive()).isTrue();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        UUID nextExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(nextExecutionId).isNotEqualTo(executionId);
        assertThat(executions.count()).isEqualTo(executionsBeforeNext + 1);
        assertThat(attempts.findByExecutionId(nextExecutionId).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.QUEUED);
    }

    @Test
    void failedEscalatedAloneIsNotSufficientProofOfASafeTerminalStop() {
        admit("50021", "MODBAR-521");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                java.util.Map.of("error", "step exhausted retries"));
        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());

        PilotOutcomeService.AttemptOutcome uncertain = outcomes.refresh(attempt.getId());

        assertThat(uncertain.state())
                .as("the escalated failure alone resolves to nothing without workload evidence")
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        PilotOutcomeService.AttemptOutcome resolved = outcomes.refresh(attempt.getId());

        assertThat(resolved.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void sharedProviderFailureProducesOneInstallationPauseInsteadOfPerTaskStops() {
        admit("50031", "MODBAR-531");
        admit("50032", "MODBAR-532");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        stateManager.transition(executionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(executionId, DevelopWorker.OUTCOME,
                "{\"status\":\"FAILED\",\"failure\":{\"code\":\"PROVIDER_FAILED\",\"message\":\"provider unreachable\"}}");
        writeArtifact(executionId, VerifyWorker.RESULT,
                "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"provider unreachable\"}");

        PilotOutcomeService.AttemptOutcome outcome = outcomes.refresh(attempt.getId());

        assertThat(outcome.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.PROCESSING_PAUSED);
        assertThat(outcome.workloadInactive()).isTrue();
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getResultReason())
                .as("the shared-pause cause is persisted on the attempt, not only in artifacts")
                .isNotBlank()
                .contains("DEVELOPMENT_FAILED: provider unreachable");
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(attempts.findDispatchable(PilotAttempt.State.RESERVED, org.springframework.data.domain.PageRequest.of(0, 10)))
                .as("the next task stays queued while the installation is paused")
                .hasSize(1);
    }

    @Test
    void engineCompletedAloneIsNotPrReadyButADeliveredPullRequestReleasesBeforeFeedback() {
        admit("50041", "MODBAR-541");
        admit("50042", "MODBAR-542");
        UUID failedExecutionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt failedAttempt = attempts.findByExecutionId(failedExecutionId).orElseThrow();
        stateManager.transition(failedExecutionId, ExecutionStatus.COMPLETED, null);
        writeArtifact(failedExecutionId, VerifyWorker.RESULT,
                "{\"state\":\"VERIFICATION_FAILED\",\"reason\":\"tests failed\"}");
        writeArtifact(failedExecutionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"NOT_RUN\",\"reason\":\"Candidate was not independently verified\"}");

        PilotOutcomeService.AttemptOutcome stopped = outcomes.refresh(failedAttempt.getId());

        assertThat(stopped.state())
                .as("engine COMPLETED with failed verification is a stopped result, not PR_READY")
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(stopped.publicationIdentity()).isNull();

        UUID deliveredExecutionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt deliveredAttempt = attempts.findByExecutionId(deliveredExecutionId).orElseThrow();
        writeArtifact(deliveredExecutionId, DeliveryWorker.DELIVERY,
                "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\"https://github.com/user/repo/pull/7\","
                        + "\"deliveryBranch\":\"factory/modbar-542\"}");

        PilotOutcomeService.AttemptOutcome ready = outcomes.refresh(deliveredAttempt.getId());

        assertThat(ready.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.PR_READY);
        assertThat(ready.publicationIdentity()).isEqualTo("https://github.com/user/repo/pull/7");
        assertThat(attempts.findById(deliveredAttempt.getId()).orElseThrow().getPublicationIdentity())
                .isEqualTo("https://github.com/user/repo/pull/7");
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId())
                .as("the slot is released while the execution is still running its feedback steps")
                .isNull();
        assertThat(stateManager.get(deliveredExecutionId).getStatus()).isEqualTo(ExecutionStatus.PENDING);
    }

    @Test
    void staleAttemptsCannotPublishInterfereWithOrMarkANewerAttemptComplete() {
        admit("50051", "MODBAR-551");
        admit("50052", "MODBAR-552");
        UUID firstExecutionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt first = attempts.findByExecutionId(firstExecutionId).orElseThrow();
        failExecution(firstExecutionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(first.getId()).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);

        UUID secondExecutionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt second = attempts.findByExecutionId(secondExecutionId).orElseThrow();

        assertThat(pilotPolicy.ownsActiveSlot(firstExecutionId)).isFalse();
        assertThat(pilotPolicy.ownsActiveSlot(secondExecutionId)).isTrue();

        pilotPolicy.recordStop(firstExecutionId, "publish", "a stale worker tried to publish");
        pilotPolicy.recordStop(secondExecutionId, "publish", "a stale worker touched the newer attempt");

        assertThat(attempts.findById(first.getId()).orElseThrow().getResultReason())
                .doesNotContain("stale worker touched");
        assertThat(attempts.findById(second.getId()).orElseThrow().getResultReason())
                .contains("stale worker touched");

        PilotOutcomeService.AttemptOutcome replay = outcomes.refresh(first.getId());

        assertThat(replay.state()).isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(second.getId());
    }

    @Test
    void recoveryResolvesTheOneActiveAttemptOnStartupOrSchedule() {
        assertThat(installations.findById(1)).isEmpty();
        recovery.reconcileActive();

        admit("50061", "MODBAR-561");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        PilotAttempt attempt = attempts.findByExecutionId(executionId).orElseThrow();
        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");

        recovery.reconcileActive();

        assertThat(attempts.findById(attempt.getId()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.STOPPED);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        assertThat(dispatcher.dispatchNext()).isEmpty();
    }

    private void failExecution(UUID executionId, String resultJson) {
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                java.util.Map.of("error", "step exhausted retries"));
        writeArtifact(executionId, VerifyWorker.RESULT, resultJson);
    }

    private void writeArtifact(UUID executionId, String name, String content) {
        artifactStore.putMarkdown(executionId, name, content, "pilot-lifecycle-test");
    }

    private UUID admit(String issueId, String displayKey) {
        PilotTask task = tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setAcceptedAuthorityFingerprint(AUTHORITY);
        installation.setObservedAuthorityFingerprint(AUTHORITY);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        var result = admissions.runSelected(new PilotCommands.RunSelected("run-" + issueId, AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        org.assertj.core.api.Assertions.assertThat(result.rejectedReasons()).isEmpty();
        return task.getId();
    }

}
