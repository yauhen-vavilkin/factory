package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.execution.PersistedPilotAttemptPolicy;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.execution.PilotPublicationGuard;
import org.folio.factory.app.pilot.execution.PilotRecoveryService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntentRepository;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidenceRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.startup.PilotRuntimeSmoke;
import org.folio.factory.app.pilot.startup.PilotStartupProperties;
import org.folio.factory.app.pilot.startup.PilotStartupService;
import org.folio.factory.app.pilot.startup.RuntimeFingerprint;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.pilot.PilotAttemptPolicy;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.VerifyWorker;
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
import org.folio.factory.core.domain.ExecutionStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Crash/restart semantics for in-flight pilot work against real PostgreSQL: a
 * freshly constructed recovery service and attempt policy — the state a restart
 * starts from — reclaim the running attempt without a new paid start, without
 * resetting the persisted deadline and only with proven workload inactivity;
 * queued work waits for startup readiness until the same start action passes,
 * while returning an existing binding stays available for reconciliation; and
 * a restart never duplicates attempt rows or executions for an admitted task.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.deadline=PT30M",
        "factory.pilot.execution.recovery-poll-interval-ms=3600000",
        "factory.pilot.execution.dispatch-interval-ms=3600000",
        "factory.pilot.source.source=981",
        "factory.pilot.source.sprint-id=4711",
        "factory.pilot.source.statuses[0]=To Do",
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotRestartIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String AUTHORITY = "authority-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    DockerWorkloads docker;

    @MockitoBean
    PilotRuntimeSmoke smoke;

    @MockitoBean
    DeveloperPricingProperties pricing;

    @MockitoBean
    RetentionProperties retention;

    @Autowired
    PilotStartupService startup;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotPublicationIntentRepository intents;

    @Autowired
    PilotReadinessEvidenceRepository evidence;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotOutcomeService outcomes;

    @Autowired
    PilotAttemptPolicy livePolicy;

    @Autowired
    StateManager stateManager;

    @Autowired
    ArtifactStore artifactStore;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        evidence.deleteAll();
        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
    }

    @Test
    void restartedRecoveryReclaimsTheRunningAttemptWithoutANewPaidStartOrDeadlineReset() {
        syncReady();
        smokePasses();
        ratesKnown();
        assertThat(startup.start().executionReady()).isTrue();
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

        new PilotRecoveryService(installations, outcomes).onStartup();
        PersistedPilotAttemptPolicy restartedPolicy = restartedPolicy();

        assertThat(restartedPolicy.claimPaidStart(executionId))
                .as("a restart replays no paid start for the claimed attempt")
                .isFalse();
        assertThat(restartedPolicy.ownsActiveSlot(executionId)).isTrue();
        assertThat(restartedPolicy.find(executionId)).isPresent();
        PilotAttempt reclaimed = attempts.findById(attemptId).orElseThrow();
        assertThat(reclaimed.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(reclaimed.getPaidStartAt()).isNotNull();
        assertThat(reclaimed.getStartedAt()).isEqualTo(startedAt);
        assertThat(reclaimed.getDeadlineAt()).isEqualTo(deadlineAt);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attemptId);
        assertThat(outcomes.refresh(attemptId).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.PROCESSING);
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 2);

        doThrow(new IllegalStateException("docker ps failed")).when(docker).cleanOwned(any(), anyString());
        new PilotRecoveryService(installations, outcomes).reconcileActive();
        assertThat(attempts.findById(attemptId).orElseThrow().getState())
                .as("a live execution stays queued while the outage only blocks resolution")
                .isEqualTo(PilotAttempt.State.QUEUED);

        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(attemptId).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.NEEDS_INSPECTION);
        assertThat(attempts.findById(attemptId).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.NEEDS_INSPECTION);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attemptId);
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);

        org.mockito.Mockito.doNothing().when(docker).cleanOwned(any(), anyString());
        new PilotRecoveryService(installations, outcomes).reconcileActive();
        PilotAttempt resolved = attempts.findById(attemptId).orElseThrow();
        assertThat(resolved.getState()).isEqualTo(PilotAttempt.State.STOPPED);
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
    void queuedWorkWaitsForReadinessAcrossRestartUntilTheStartActionPasses() {
        syncReady();
        UUID queuedTask = admit("90011", "MODBAR-911");
        admit("90012", "MODBAR-912");

        assertThatThrownBy(dispatcher::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime check has not completed");
        assertThat(attempts.findLiveByTaskId(queuedTask))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED));
        long executionsBefore = executions.count();
        assertThat(executions.count()).isEqualTo(executionsBefore);

        smokePasses();
        ratesKnown();
        assertThat(startup.start().executionReady()).isTrue();
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.findByExecutionId(executionId).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.QUEUED);

        evidence.deleteAll();
        assertThat(dispatcher.dispatchNext()).as("an existing binding stays available for reconciliation")
                .contains(executionId);

        failExecution(executionId, "{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"syntax\"}");
        assertThat(outcomes.refresh(attempts.findByExecutionId(executionId).orElseThrow().getId()).state())
                .isEqualTo(PilotOutcomeService.AttemptOutcome.State.STOPPED);

        assertThatThrownBy(dispatcher::dispatchNext)
                .as("a genuinely new launch waits for readiness again")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Runtime check has not completed");
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);

        assertThat(startup.start().executionReady()).isTrue();
        UUID secondExecutionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(secondExecutionId).isNotEqualTo(executionId);
        assertThat(executions.count()).isEqualTo(executionsBefore + 2);
        verify(smoke, times(2)).check(any(RuntimeFingerprint.class));
    }

    @Test
    void restartResolvesAdmissionAndDispatchIdempotentlyWithoutDuplicateRows() {
        syncReady();
        smokePasses();
        ratesKnown();
        assertThat(startup.start().executionReady()).isTrue();
        long attemptsBefore = attempts.count();
        long executionsBefore = executions.count();
        UUID taskId = admit("90021", "MODBAR-921");
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 1);

        new PilotRecoveryService(installations, outcomes).onStartup();
        PilotTask task = tasks.findById(taskId).orElseThrow();

        var repeat = admissions.runSelected(new PilotCommands.RunSelected("run-90021", AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(repeat.admittedTaskIds()).containsExactly(taskId);
        assertThat(attempts.count()).isEqualTo(1);

        var conflicting = admissions.runSelected(new PilotCommands.RunSelected("run-90021-late", AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(conflicting.admittedTaskIds()).isEmpty();
        assertThat(conflicting.rejectedReasons().get(taskId)).contains("already has a live admission");
        assertThat(attempts.count()).isEqualTo(attemptsBefore + 1);

        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(dispatcher.dispatchNext()).contains(executionId);
        assertThat(executions.count()).isEqualTo(executionsBefore + 1);
        assertThat(attempts.findByExecutionId(executionId).orElseThrow().getExecutionId())
                .isEqualTo(executionId);
    }

    private PersistedPilotAttemptPolicy restartedPolicy() {
        return new PersistedPilotAttemptPolicy(attempts, tasks, installations, intents,
                new PilotPublicationGuard(attempts, tasks), transactionManager);
    }

    private void failExecution(UUID executionId, String resultJson) {
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        artifactStore.putMarkdown(executionId, VerifyWorker.RESULT, resultJson, "pilot-restart-test");
    }

    private void syncReady() {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installation.setObservedAuthorityFingerprint(AUTHORITY);
        installations.save(installation);
    }

    private void smokePasses() {
        when(smoke.check(any())).thenAnswer(invocation ->
                passed(stubFingerprint(invocation.getArgument(0))));
    }

    private void ratesKnown() {
        when(pricing.rateFor(any())).thenReturn(Optional.of(new DeveloperPricingProperties.Rate(
                "claude-sonnet-5-5", "USD", LocalDate.of(2026, 10, 22),
                URI.create("https://pricing.fixture.invalid"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE)));
    }

    private static PilotRuntimeSmoke.SmokeEvidence passed(RuntimeFingerprint fingerprint) {
        return new PilotRuntimeSmoke.SmokeEvidence(fingerprint.identity(), fingerprint.image(),
                fingerprint.runtime(), fingerprint.model(), fingerprint.endpointOrigin(),
                fingerprint.instructionsFingerprint(), true, true, true, true, null, Instant.now());
    }

    /** The matcher call during re-stubbing passes null; answer with the defaulted local profile. */
    private static RuntimeFingerprint stubFingerprint(RuntimeFingerprint fingerprint) {
        return fingerprint != null ? fingerprint
                : RuntimeFingerprint.of(new DevRuntimeProperties.ClaudeCode(null, null, null, null,
                        null, null, null, null, null, null),
                        PilotStartupProperties.DEFAULT_SMOKE_MAX_TURNS, 120,
                        PilotRuntimeSmoke.instructionsFingerprint());
    }

    private UUID admit(String issueId, String displayKey) {
        PilotTask task = tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
        syncReady();
        var result = admissions.runSelected(new PilotCommands.RunSelected("run-" + issueId, AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        return task.getId();
    }
}
