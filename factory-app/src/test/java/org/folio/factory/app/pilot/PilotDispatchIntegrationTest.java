package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotExecutionLauncher;
import org.folio.factory.app.pilot.execution.PilotExecutionProperties;
import org.folio.factory.app.pilot.execution.PilotOutcomeService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.core.limits.DedupKeyDeriver;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.PreparedManualTrigger;
import org.folio.factory.core.trigger.TriggerPreflight;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Serial dispatch against real PostgreSQL: one queued attempt at a time gets one
 * engine execution, bound atomically with the active reservation, and repeated
 * dispatch returns the same binding. The router dedup window is disabled, so the
 * repeated-dispatch assertions prove the active-slot mechanism, not router
 * dedup. A crash after execution creation rolls the execution, binding and
 * reservation back together. Enabled retention refuses a new launch before any
 * execution exists.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotDispatchIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String FLOW_ID = "dev-factory";
    private static final Instant JIRA_LATER = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant JIRA_EARLIER = Instant.parse("2026-10-01T09:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    FlowRegistry flowRegistry;

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
    PipelineRouter router;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    LimitsProperties limits;

    @Autowired
    DedupKeyDeriver dedupKeyDeriver;

    @Autowired
    AuditLog auditLog;

    @Autowired
    List<TriggerPreflight> preflights;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        executions.deleteAll();
    }

    @Test
    void dispatchLaunchesOnlyTheNextAttemptAndRepeatsTheSameBinding() {
        UUID firstTask = admit("30001", "MODBAR-202");
        UUID secondTask = admit("30002", "MODBAR-201");
        // Jira creation times and display keys disagree with the click order, so
        // dispatching the first-clicked task proves the queue follows the clicks.
        setJiraCreated(firstTask, JIRA_LATER);
        setJiraCreated(secondTask, JIRA_EARLIER);

        Optional<UUID> executionId = dispatcher.dispatchNext();

        assertThat(executionId).isPresent();
        var execution = stateManager.get(executionId.get());
        assertThat(execution.getFlowId()).isEqualTo(FLOW_ID);
        assertThat(execution.getTriggerPayload()).contains("MODBAR-202");
        PilotAttempt active = attempts.findByExecutionId(executionId.get()).orElseThrow();
        assertThat(active.getTaskId()).isEqualTo(firstTask);
        assertThat(active.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(execution.getDedupKey()).isEqualTo("pilot-attempt=" + active.getId());
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(active.getId());

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(secondTask))
                .singleElement()
                .satisfies(queued -> {
                    assertThat(queued.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(queued.getExecutionId()).isNull();
                });
        assertThat(executions.count()).isEqualTo(1);

        assertThat(dispatcher.dispatchNext()).isEqualTo(executionId);
        assertThat(dispatcher.dispatchNext()).isEqualTo(executionId);
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void crashAfterExecutionCreationRollsBackExecutionBindingAndReservation() {
        UUID taskId = admit("30011", "MODBAR-211");
        PilotDispatcher crashing = dispatcherWithRouter(crashingAfterCreation());

        assertThatThrownBy(crashing::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated crash");

        assertThat(executions.count()).isZero();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getExecutionId()).isNull();
                });
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        assertThat(dispatcher.dispatchNext()).isPresent();
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void failedScanDoesNotBlockDispatch() {
        admit("30021", "MODBAR-221");
        PilotInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        assertThat(dispatcher.dispatchNext()).isPresent();
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void taskNoLongerAssignedStillStartsItsQueuedRun() {
        UUID taskId = admit("30031", "MODBAR-231");
        PilotTask task = tasks.findById(taskId).orElseThrow();
        task.setInScope(false);
        tasks.save(task);

        Optional<UUID> executionId = dispatcher.dispatchNext();

        assertThat(executionId).isPresent();
        PilotAttempt started = attempts.findByTaskIdOrderByOrdinalAsc(taskId).getFirst();
        assertThat(started.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(started.getExecutionId()).isEqualTo(executionId.get());
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(started.getId());
    }

    @Test
    void failedPreflightBlocksDispatchBeforeAnyExecution() {
        doThrow(new IllegalArgumentException("runtime not ready")).when(preflight).requireReady(eq(FLOW_ID), any());
        admit("30041", "MODBAR-241");

        assertThatThrownBy(dispatcher::dispatchNext)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime not ready");

        assertThat(executions.count()).isZero();
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
        // With execution switched off the page says so instead of leaving the task queued silently.
        assertThat(dispatcher.queueProblem()).contains("FACTORY_PILOT_EXECUTION_ENABLED=true");
    }

    @Test
    void aQueuedTaskThatCannotRunStopsAndTheNextTaskStarts() {
        UUID moved = admit("30061", "MODBAR-261");
        UUID next = admit("30062", "MODBAR-262");
        doThrow(new DeveloperPreflight.TaskNotRunnableException("Jira did not return the requested issue."))
                .when(preflight).requireReady(eq(FLOW_ID),
                        argThat(payload -> "MODBAR-261".equals(payload.path("issueKey").asString())));

        Optional<UUID> executionId = dispatcher.dispatchNext();

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(moved)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.STOPPED);
            assertThat(attempt.getResultReason())
                    .isEqualTo("Could not start: Jira did not return the requested issue.");
            assertThat(attempt.getExecutionId()).isNull();
        });
        PilotAttempt started = attempts.findByTaskIdOrderByOrdinalAsc(next).getFirst();
        assertThat(executionId).contains(started.getExecutionId());
        assertThat(started.getState()).isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void enabledRetentionRefusesANewLaunchBeforeAnyExecution() {
        UUID taskId = admit("30051", "MODBAR-251");
        var enabled = new PilotExecutionProperties(null, true);
        var launcher = new PilotExecutionLauncher(router, tasks, attempts, installations, enabled,
                new RetentionProperties(true, null, null, null), transactionManager);
        var guarded = new PilotDispatcher(attempts, installations, launcher, enabled, outcomesProvider());

        assertThat(guarded.queueProblem()).isNull();
        assertThatThrownBy(guarded::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FACTORY_RETENTION_ENABLED=true");
        // The refusal reaches the page until a later dispatch succeeds.
        assertThat(guarded.queueProblem()).contains("FACTORY_RETENTION_ENABLED=true", "tries again");

        assertThat(executions.count()).isZero();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED));
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void emptyQueueDispatchesNothing() {
        assertThat(dispatcher.dispatchNext()).isEmpty();
        assertThat(executions.count()).isZero();
    }

    private PilotDispatcher dispatcherWithRouter(PipelineRouter crashingRouter) {
        var launcher = new PilotExecutionLauncher(crashingRouter, tasks, attempts, installations,
                new PilotExecutionProperties(null), new RetentionProperties(null, null, null, null),
                transactionManager);
        return new PilotDispatcher(attempts, installations, launcher,
                new PilotExecutionProperties(null, false), outcomesProvider());
    }

    private org.springframework.beans.factory.ObjectProvider<PilotOutcomeService> outcomesProvider() {
        org.springframework.beans.factory.ObjectProvider<PilotOutcomeService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private PipelineRouter crashingAfterCreation() {
        return new PipelineRouter(flowRegistry, stateManager, jsonMapper, limits, dedupKeyDeriver, auditLog,
                preflights) {
            @Override
            public UUID routePrepared(PreparedManualTrigger prepared) {
                super.routePrepared(prepared);
                throw new IllegalStateException("simulated crash after createExecution");
            }
        };
    }

    private UUID admit(String issueId, String displayKey) {
        PilotTask task = tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        admissions.runSelected(new PilotCommands.RunSelected("run-" + issueId, List.of(selectionFor(task))));
        return task.getId();
    }

    private void setJiraCreated(UUID taskId, Instant jiraCreatedAt) {
        PilotTask task = tasks.findById(taskId).orElseThrow();
        task.setJiraCreatedAt(jiraCreatedAt);
        tasks.save(task);
    }

    private PilotCommands.Selection selectionFor(PilotTask task) {
        return new PilotCommands.Selection(task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task), PilotAdmissionService.destinationFingerprint(task));
    }
}
