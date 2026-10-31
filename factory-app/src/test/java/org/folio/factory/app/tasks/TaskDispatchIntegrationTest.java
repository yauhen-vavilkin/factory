package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.execution.TaskExecutionLauncher;
import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.app.tasks.execution.TaskOutcomeService;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
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
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.support.SharedDatabase;

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
@Import({StubLlmConfiguration.class, SharedDatabase.class})
@DirtiesContext
class TaskDispatchIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String FLOW_ID = "dev-factory";
    private static final Instant JIRA_LATER = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant JIRA_EARLIER = Instant.parse("2026-10-01T09:00:00Z");

    @MockitoBean
    DeveloperPreflight preflight;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    FlowRegistry flowRegistry;

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
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
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
        TaskAttempt active = attempts.findByExecutionId(executionId.get()).orElseThrow();
        assertThat(active.getTaskId()).isEqualTo(firstTask);
        assertThat(active.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(execution.getDedupKey()).isEqualTo("task-attempt=" + active.getId());
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(active.getId());

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(secondTask))
                .singleElement()
                .satisfies(queued -> {
                    assertThat(queued.getState()).isEqualTo(TaskAttempt.State.RESERVED);
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
        TaskDispatcher crashing = dispatcherWithRouter(crashingAfterCreation());

        assertThatThrownBy(crashing::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated crash");

        assertThat(executions.count()).isZero();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.RESERVED);
                    assertThat(attempt.getExecutionId()).isNull();
                });
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();

        assertThat(dispatcher.dispatchNext()).isPresent();
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void failedScanDoesNotBlockDispatch() {
        admit("30021", "MODBAR-221");
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        assertThat(dispatcher.dispatchNext()).isPresent();
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void taskNoLongerAssignedStillStartsItsQueuedRun() {
        UUID taskId = admit("30031", "MODBAR-231");
        Task task = tasks.findById(taskId).orElseThrow();
        task.setInScope(false);
        tasks.save(task);

        Optional<UUID> executionId = dispatcher.dispatchNext();

        assertThat(executionId).isPresent();
        TaskAttempt started = attempts.findByTaskIdOrderByOrdinalAsc(taskId).getFirst();
        assertThat(started.getState()).isEqualTo(TaskAttempt.State.QUEUED);
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
        assertThat(dispatcher.queueProblem()).contains("FACTORY_TASKS_EXECUTION_ENABLED=true");
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
            assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.STOPPED);
            assertThat(attempt.getResultReason())
                    .isEqualTo("Could not start: Jira did not return the requested issue.");
            assertThat(attempt.getExecutionId()).isNull();
        });
        TaskAttempt started = attempts.findByTaskIdOrderByOrdinalAsc(next).getFirst();
        assertThat(executionId).contains(started.getExecutionId());
        assertThat(started.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void aLongCouldNotStartReasonFitsTheResultReasonColumn() {
        UUID moved = admit("30063", "MODBAR-263");
        doThrow(new DeveloperPreflight.TaskNotRunnableException("x".repeat(800)))
                .when(preflight).requireReady(eq(FLOW_ID), any());

        dispatcher.dispatchNext();

        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(moved)).singleElement().satisfies(attempt -> {
            assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.STOPPED);
            assertThat(attempt.getResultReason()).startsWith("Could not start: xxx").hasSize(500);
        });
    }

    @Test
    void enabledRetentionRefusesANewLaunchBeforeAnyExecution() {
        UUID taskId = admit("30051", "MODBAR-251");
        var enabled = new TaskExecutionProperties(null, true);
        var launcher = new TaskExecutionLauncher(router, tasks, attempts, installations, enabled,
                new RetentionProperties(true, null, null, null), transactionManager);
        var guarded = new TaskDispatcher(attempts, installations, launcher, enabled, outcomesProvider());

        assertThat(guarded.queueProblem()).isNull();
        assertThatThrownBy(guarded::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FACTORY_RETENTION_ENABLED=true");
        // The refusal reaches the page until a later dispatch succeeds.
        assertThat(guarded.queueProblem()).contains("FACTORY_RETENTION_ENABLED=true", "tries again");

        assertThat(executions.count()).isZero();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(taskId))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(TaskAttempt.State.RESERVED));
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isNull();
    }

    @Test
    void emptyQueueDispatchesNothing() {
        assertThat(dispatcher.dispatchNext()).isEmpty();
        assertThat(executions.count()).isZero();
    }

    private TaskDispatcher dispatcherWithRouter(PipelineRouter crashingRouter) {
        var launcher = new TaskExecutionLauncher(crashingRouter, tasks, attempts, installations,
                new TaskExecutionProperties(null, false), new RetentionProperties(null, null, null, null),
                transactionManager);
        return new TaskDispatcher(attempts, installations, launcher,
                new TaskExecutionProperties(null, false), outcomesProvider());
    }

    private org.springframework.beans.factory.ObjectProvider<TaskOutcomeService> outcomesProvider() {
        org.springframework.beans.factory.ObjectProvider<TaskOutcomeService> provider =
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
        Task task = tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar"));
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
        admissions.runSelected(new TaskCommands.RunSelected("run-" + issueId, List.of(selectionFor(task)), null));
        return task.getId();
    }

    private void setJiraCreated(UUID taskId, Instant jiraCreatedAt) {
        Task task = tasks.findById(taskId).orElseThrow();
        task.setJiraCreatedAt(jiraCreatedAt);
        tasks.save(task);
    }

    private TaskCommands.Selection selectionFor(Task task) {
        return new TaskCommands.Selection(task.getId(), task.getVersion(),
                TaskAdmissionChecks.inputFingerprint(task), TaskAdmissionChecks.destinationFingerprint(task));
    }
}
