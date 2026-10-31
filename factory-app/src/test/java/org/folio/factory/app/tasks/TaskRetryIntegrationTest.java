package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.admission.TaskRetryService;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.folio.factory.app.tasks.source.TaskJiraInstance;
import org.folio.factory.app.tasks.source.TaskSource;
import org.folio.factory.app.tasks.source.TaskSourceProperties;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Explicit fresh Retry against real PostgreSQL: a STOPPED latest attempt can
 * retry, and a withdrawn latest attempt stays retriable once earlier real work
 * exists. The retry freezes the current bounded input into a new RETRY attempt
 * whose launch reuses the serial dispatch path for a new execution, and
 * duplicate or stale clicks never create a second attempt. Started work holds
 * whatever happens in Jira; only a destination change before start stops a
 * queued retry, and a token without push access refuses it up front.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S",
        "factory.tasks.execution.deadline=PT30M",
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.dev-factory.repositories.mod-bar.source-repo=folio-org/mod-bar",
        "factory.dev-factory.repositories.mod-bar.base-branch=master",
        "factory.dev-factory.repositories.mod-bar.build-image=maven:3.9-eclipse-temurin-21",
        "factory.dev-factory.repositories.mod-bar.verification-plan=java21-unit",
        "factory.dev-factory.repositories.mod-bar.jira-projects[0]=MODBAR"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TaskRetryIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final Instant EARLIER = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-01T10:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    /** The application's Jira reads go to the same stub the tests drive. */
    @MockitoBean
    JiraSourceConnector jira;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskAdmissionService admissions;

    @Autowired
    TaskRetryService retries;

    @Autowired
    TaskDispatcher dispatcher;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    TaskJiraInstance jiraInstance;

    @Autowired
    DevFactoryProperties devFactory;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final StubSource source = new StubSource();
    private TaskDiscoveryService discovery;

    @BeforeEach
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        executions.deleteAll();
        source.reset();
        discovery = discovery(devFactory);
        when(jira.searchIssues(any(), any())).thenAnswer(call -> source.searchIssues(call.getArgument(0),
                call.getArgument(1)));
    }

    @Test
    void retryOfATaskAddedByKeyRunsOnWhatJiraSaysAtTheClick() {
        source.issues = List.of(issueWithSummary("50091", "MODBAR-591", EARLIER, "Which limit applies?"));
        var added = admissions.runByKey("by-key-591", "MODBAR-591");
        assertThat(added.problem()).isNull();
        Task task = tasks.findById(added.taskId()).orElseThrow();
        forceState(task, TaskAttempt.State.STOPPED, "coding: the limit is not specified");
        source.issues = List.of(issueWithSummary("50091", "MODBAR-591", EARLIER, "The limit is 100 per page"));

        var click = retries.retry(new TaskCommands.Retry("retry-591", task.getId(), task.getVersion(),
                TaskAdmissionService.inputFingerprint(task), TaskAdmissionService.destinationFingerprint(task)));

        TaskAttempt retry = attempts.findById(click.attemptId()).orElseThrow();
        assertThat(retry.getKind()).isEqualTo(TaskAttempt.Kind.RETRY);
        assertThat(retry.getApprovedSnapshot()).contains("The limit is 100 per page");
        Task refreshed = tasks.findById(task.getId()).orElseThrow();
        assertThat(refreshed.getTitle()).isEqualTo("The limit is 100 per page");
        assertThat(refreshed.isInScope()).as("a task added by key stays outside My tasks").isFalse();
    }

    @Test
    void retryOfStoppedAttemptReservesFreshAttemptAndLaunchesANewExecution() {
        source.issues = List.of(issue("50001", "MODBAR-501", EARLIER));
        discovery.sync();
        Task task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50001").orElseThrow();
        stopWithFailedInitialAttempt(task, "run-501");
        UUID previousAttemptId = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst().getId();

        var click = retries.retry(new TaskCommands.Retry("retry-501", task.getId(), task.getVersion(),
                TaskAdmissionService.inputFingerprint(task),
                TaskAdmissionService.destinationFingerprint(task)));
        UUID newAttemptId = click.attemptId();

        assertThat(newAttemptId).isNotEqualTo(previousAttemptId);
        // Task execution is off in this context, so the click only queues the retry.
        assertThat(click.state()).isEqualTo("RESERVED");
        assertThat(click.executionId()).isNull();
        assertThat(executions.count()).isZero();
        UUID executionId = dispatcher.dispatchNext().orElseThrow();
        var execution = stateManager.get(executionId);
        assertThat(execution.getFlowId()).isEqualTo("dev-factory");
        assertThat(execution.getTriggerPayload()).contains("MODBAR-501");
        assertThat(execution.getDedupKey()).isEqualTo("task-attempt=" + newAttemptId);
        assertThat(executions.count()).isEqualTo(1);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(newAttemptId);

        List<TaskAttempt> attemptHistory = attempts.findByTaskIdOrderByOrdinalAsc(task.getId());
        assertThat(attemptHistory).hasSize(2);
        TaskAttempt retry = attemptHistory.get(1);
        assertThat(retry.getKind()).isEqualTo(TaskAttempt.Kind.RETRY);
        assertThat(retry.getOrdinal()).isEqualTo(2);
        assertThat(retry.getAdmittedVia()).isEqualTo(TaskAttempt.AdmittedVia.MANUAL);
        assertThat(retry.getInputFingerprint()).isEqualTo(TaskAdmissionService.inputFingerprint(task));
        assertThat(retry.getDestinationFingerprint())
                .isEqualTo(TaskAdmissionService.destinationFingerprint(task));
        assertThat(retry.getApprovedSnapshot()).isEqualTo(task.getIssueSnapshot());
        assertThat(retry.getStartedAt()).isNotNull();
        assertThat(retry.getDeadlineAt()).isEqualTo(retry.getStartedAt().plus(Duration.ofMinutes(30)));

        TaskAttempt previous = attemptHistory.get(0);
        assertThat(previous.getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(previous.getResultReason()).isEqualTo("coding: verification failed");
        assertThat(previous.getExecutionId()).isNull();
    }

    @Test
    void duplicateRetryClickResolvesToTheStoredAttemptWithoutCreatingAnotherOne() {
        source.issues = List.of(issue("50011", "MODBAR-511", EARLIER));
        discovery.sync();
        Task task = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50011").orElseThrow();
        stopWithFailedInitialAttempt(task, "run-511");

        var firstClick = retries.retry(new TaskCommands.Retry("retry-511", task.getId(), task.getVersion(),
                TaskAdmissionService.inputFingerprint(task),
                TaskAdmissionService.destinationFingerprint(task)));
        var secondClick = retries.retry(new TaskCommands.Retry("retry-511", task.getId(), task.getVersion(),
                TaskAdmissionService.inputFingerprint(task),
                TaskAdmissionService.destinationFingerprint(task)));

        assertThat(secondClick.attemptId()).isEqualTo(firstClick.attemptId());
        assertThat(secondClick.executionId()).isEqualTo(firstClick.executionId());
        assertThat(secondClick.state()).isEqualTo("RESERVED");
        assertThat(executions.count()).isZero();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(2);
    }

    @Test
    void retryEligibilityFollowsTheLatestAttemptState() {
        source.issues = List.of(issue("50021", "MODBAR-521", EARLIER));
        discovery.sync();

        Task queued = directTask("50022", "MODBAR-522");
        admit(queued, "run-522");
        Task running = directTask("50023", "MODBAR-523");
        admit(running, "run-523");
        forceState(running, TaskAttempt.State.RUNNING, null);
        Task inspecting = directTask("50024", "MODBAR-524");
        admit(inspecting, "run-524");
        forceState(inspecting, TaskAttempt.State.NEEDS_INSPECTION,
                "publish: the candidate was delivered without a known pull-request identity");
        Task delivered = directTask("50025", "MODBAR-525");
        admit(delivered, "run-525");
        forceState(delivered, TaskAttempt.State.PR_READY, null);
        Task withdrawn = directTask("50026", "MODBAR-526");
        admit(withdrawn, "run-526");
        forceState(withdrawn, TaskAttempt.State.WITHDRAWN, "task left the My tasks scope before start");
        Task neverAttempted = directTask("50027", "MODBAR-527");
        Task unsupported = directTask("50029", "MODBAR-529");
        stopWithFailedInitialAttempt(unsupported, "run-529");
        Task routed = tasks.findById(unsupported.getId()).orElseThrow();
        routed.setEligibility(Task.Eligibility.INELIGIBLE);
        unsupported = tasks.save(routed);

        assertThat(retryRejected(queued, "live attempt")).isEqualTo("RESERVED");
        assertThat(retryRejected(running, "live attempt")).isEqualTo("RUNNING");
        assertThat(retryRejected(inspecting, "needs inspection")).isEqualTo("NEEDS_INSPECTION");
        assertThat(retryRejected(delivered, "delivered a pull request")).isEqualTo("PR_READY");
        assertThat(retryRejected(withdrawn, "select the task again")).isEqualTo("WITHDRAWN");
        assertThat(retryRejected(neverAttempted, "never been attempted")).isNull();
        assertThat(retryRejected(unsupported, "not eligible")).isEqualTo("STOPPED");

        Task stale = directTask("50030", "MODBAR-530");
        stopWithFailedInitialAttempt(stale, "run-530");
        var staleClick = retries.retry(new TaskCommands.Retry("retry-530", stale.getId(), stale.getVersion(),
                "stale-input-fingerprint", TaskAdmissionService.destinationFingerprint(stale)));
        assertThat(staleClick.explanation()).contains("changed since the retry was requested");
        assertThat(staleClick.attemptId())
                .isEqualTo(attempts.findByTaskIdOrderByOrdinalAsc(stale.getId()).getFirst().getId());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(stale.getId())).hasSize(1);

        assertThat(attempts.count()).isEqualTo(7);
        assertThat(executions.count()).isZero();
    }

    @Test
    void failedScanAndATaskNoLongerAssignedDoNotBlockARetry() {
        source.issues = List.of(issue("50041", "MODBAR-541", EARLIER), issue("50042", "MODBAR-542", LATER));
        discovery.sync();
        Task target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50041").orElseThrow();
        Task holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50042").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-541");
        admitAndDispatch(holder, "run-542");
        Task unassigned = tasks.findById(target.getId()).orElseThrow();
        unassigned.setInScope(false);
        unassigned = tasks.save(unassigned);
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        var click = retries.retry(new TaskCommands.Retry("retry-541", unassigned.getId(), unassigned.getVersion(),
                TaskAdmissionService.inputFingerprint(unassigned),
                TaskAdmissionService.destinationFingerprint(unassigned)));
        assertThat(click.state()).isEqualTo("RESERVED");

        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isPresent();
        TaskAttempt started = attempts.findById(click.attemptId()).orElseThrow();
        assertThat(started.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(started.getExecutionId()).isNotNull();
    }

    @Test
    void queuedRetryStartsOnTheSnapshotApprovedAtTheClickEvenAfterJiraChanged() {
        source.issues = List.of(issue("50051", "MODBAR-551", EARLIER), issue("50052", "MODBAR-552", LATER));
        discovery.sync();
        Task target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50051").orElseThrow();
        Task holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50052").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-551");
        admitAndDispatch(holder, "run-552");
        String approvedSnapshot = target.getIssueSnapshot();

        var click = retries.retry(new TaskCommands.Retry("retry-551", target.getId(), target.getVersion(),
                TaskAdmissionService.inputFingerprint(target), TaskAdmissionService.destinationFingerprint(target)));
        assertThat(click.state()).isEqualTo("RESERVED");

        source.issues = List.of(issueWithSummary("50051", "MODBAR-551", EARLIER, "Revised summary of 551"),
                issue("50052", "MODBAR-552", LATER));
        discovery.sync();
        // A scan leaves a task with queued work as it was approved until the work ends.
        assertThat(tasks.findById(target.getId()).orElseThrow().getIssueSnapshot()).isEqualTo(approvedSnapshot);

        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isPresent();
        TaskAttempt started = attempts.findById(click.attemptId()).orElseThrow();
        assertThat(started.getState()).isEqualTo(TaskAttempt.State.QUEUED);
        assertThat(started.getApprovedSnapshot()).isEqualTo(approvedSnapshot);
    }

    @Test
    void queuedRetryStopsWhenTheDestinationChangedBeforeStart() {
        source.issues = List.of(issue("50061", "MODBAR-561", EARLIER), issue("50062", "MODBAR-562", LATER));
        discovery.sync();
        Task target = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50061").orElseThrow();
        Task holder = tasks.findByJiraInstanceAndIssueId(INSTANCE, "50062").orElseThrow();
        stopWithFailedInitialAttempt(target, "run-561");
        admitAndDispatch(holder, "run-562");
        var click = retries.retry(new TaskCommands.Retry("retry-561", target.getId(), target.getVersion(),
                TaskAdmissionService.inputFingerprint(target), TaskAdmissionService.destinationFingerprint(target)));
        assertThat(click.state()).isEqualTo("RESERVED");

        Task rerouted = tasks.findById(target.getId()).orElseThrow();
        rerouted.setRoutingReason("repository mod-baz");
        tasks.save(rerouted);
        releaseSlot();
        assertThat(dispatcher.dispatchNext()).isEmpty();

        TaskAttempt stopped = attempts.findById(click.attemptId()).orElseThrow();
        assertThat(stopped.getState()).isEqualTo(TaskAttempt.State.STOPPED);
        assertThat(stopped.getResultReason()).contains("destination changed", "run a fresh explicit Retry");
        assertThat(stopped.getExecutionId()).isNull();
        assertThat(executions.count()).isEqualTo(1);
    }

    @Test
    void tokenWithoutPushAccessRefusesTheRetryBeforeAnythingIsQueued() {
        Task task = directTask("50071", "MODBAR-571");
        stopWithFailedInitialAttempt(task, "run-571");
        doThrow(new IllegalStateException("Your GitHub token cannot push to folio-org/mod-bar"))
                .when(preflight).requirePushAccess("mod-bar");

        var click = retries.retry(new TaskCommands.Retry("retry-571", task.getId(), task.getVersion(),
                TaskAdmissionService.inputFingerprint(task), TaskAdmissionService.destinationFingerprint(task)));

        assertThat(click.explanation()).isEqualTo("Your GitHub token cannot push to folio-org/mod-bar");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(task.getId())).hasSize(1);
        assertThat(executions.count()).isZero();
    }

    @Test
    void invalidRequestIdIsRejectedWithoutCreatingAttempts() {
        assertThatThrownBy(() -> retries.retry(new TaskCommands.Retry(" ", UUID.randomUUID(), 0, "input",
                "destination")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id is required");
        assertThatThrownBy(() -> retries.retry(new TaskCommands.Retry("r".repeat(64), UUID.randomUUID(), 0,
                "input", "destination")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 63 characters");

        assertThat(attempts.count()).isZero();
    }

    private String retryRejected(Task task, String expectedReasonPart) {
        var click = retries.retry(new TaskCommands.Retry("retry-reject-" + task.getIssueId(), task.getId(),
                task.getVersion(), TaskAdmissionService.inputFingerprint(task),
                TaskAdmissionService.destinationFingerprint(task)));
        assertThat(click.explanation()).contains(expectedReasonPart);
        assertThat(click.executionId()).isNull();
        return click.state();
    }

    private Task directTask(String issueId, String displayKey) {
        return tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar"));
    }

    private void stopWithFailedInitialAttempt(Task task, String runId) {
        admit(task, runId);
        forceState(task, TaskAttempt.State.STOPPED, "coding: verification failed");
    }

    private void admit(Task task, String runId) {
        var result = admissions.runSelected(new TaskCommands.RunSelected(runId,
                List.of(new TaskCommands.Selection(task.getId(), task.getVersion(),
                        TaskAdmissionService.inputFingerprint(task),
                        TaskAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        assertThat(result.admittedTaskIds()).containsExactly(task.getId());
    }

    private void admitAndDispatch(Task task, String runId) {
        admit(task, runId);
        assertThat(dispatcher.dispatchNext()).isPresent();
    }

    private void forceState(Task task, TaskAttempt.State state, String reason) {
        TaskAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
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
        TaskAttempt active = attempts.findById(activeId).orElseThrow();
        active.setState(TaskAttempt.State.STOPPED);
        active.setResultReason("coding: verification failed");
        attempts.save(active);
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setActiveAttemptId(null);
        installations.save(installation);
    }

    private TaskDiscoveryService discovery(DevFactoryProperties repositories) {
        return new TaskDiscoveryService(new TaskSource(source, new TaskSourceProperties("Jane Developer"), jiraInstance),
                new RepositoryPolicy(repositories), tasks, installations, attempts, transactionManager);
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

        private List<DiscoveredIssue> issues = List.of();

        void reset() {
            issues = List.of();
        }

        @Override
        public IssuePage searchIssues(String jql, String cursor) {
            return new IssuePage(issues, null);
        }
    }
}
