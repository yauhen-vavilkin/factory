package org.folio.factory.app.tasks;

import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.worker.VerifyWorker;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task rows the task tests start from: the installation slot migration V12 seeds, tasks on
 * {@value #INSTANCE} in project MODBAR routed to mod-bar unless a test says otherwise, and
 * admission through the real service.
 */
public final class TaskFixture {

    public static final String INSTANCE = "jira.example.org";

    private final TaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final TaskInstallationRepository installations;
    private final TaskAdmissionService admissions;

    public TaskFixture(TaskRepository tasks, TaskAttemptRepository attempts,
                       TaskInstallationRepository installations, TaskAdmissionService admissions) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.admissions = admissions;
    }

    /** An eligible MODBAR task routed to mod-bar, not saved. */
    public static Task task(String issueId, String displayKey) {
        return task(issueId, displayKey, Task.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    /** An eligible MODBAR task routed to mod-bar on another Jira instance, not saved. */
    public static Task taskOn(String instance, String issueId, String displayKey) {
        return new Task(instance, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar");
    }

    /** A MODBAR task with the given routing, not saved. */
    public static Task task(String issueId, String displayKey, Task.Eligibility eligibility, String routingReason) {
        return task("MODBAR", issueId, displayKey, "Title of " + displayKey, "{\"key\": \"" + displayKey + "\"}",
                eligibility, routingReason);
    }

    /** A task with its own project, title and Jira snapshot, not saved. */
    public static Task task(String project, String issueId, String displayKey, String title, String snapshot,
                            Task.Eligibility eligibility, String routingReason) {
        return new Task(INSTANCE, issueId, displayKey, title, project, snapshot, "revision-" + issueId,
                eligibility, routingReason);
    }

    /** An initial attempt, not saved. */
    public static TaskAttempt attempt(UUID taskId, int ordinal, TaskAttempt.State state, String requestId) {
        return new TaskAttempt(taskId, ordinal, TaskAttempt.Kind.INITIAL, requestId, state);
    }

    public static TaskCommands.Selection selection(Task task) {
        return new TaskCommands.Selection(task.getId(), task.getVersion(),
                TaskAdmissionChecks.inputFingerprint(task), TaskAdmissionChecks.destinationFingerprint(task));
    }

    /** Empties the task tables and restores the installation row. */
    public void reset() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
    }

    /** Records a complete scan without a problem, which admission requires. */
    public void completeSync() {
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
    }

    /** Asks to run one task; the caller decides what the outcome must be. */
    public TaskAdmissionService.AdmissionResult runSelected(String runId, Task task) {
        return admissions.runSelected(new TaskCommands.RunSelected(runId, List.of(selection(task)), null));
    }

    /** Saves the task, completes the sync and asks to run it as {@code run-<issue id>}, without checking. */
    public UUID requestRun(Task task) {
        Task saved = tasks.save(task);
        completeSync();
        runSelected("run-" + saved.getIssueId(), saved);
        return saved.getId();
    }

    /** Saves the task, completes the sync and admits it as {@code run-<issue id>}. */
    public UUID admit(Task task) {
        Task saved = tasks.save(task);
        completeSync();
        admit(saved, "run-" + saved.getIssueId());
        return saved.getId();
    }

    /** Admits a saved task; admission must not refuse it. */
    public TaskAdmissionService.AdmissionResult admit(Task task, String runId) {
        var result = runSelected(runId, task);
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        return result;
    }

    /** Moves the task's first attempt to a state, as if its run had ended there. */
    public void forceState(Task task, TaskAttempt.State state, String reason) {
        TaskAttempt attempt = attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).getFirst();
        attempt.setState(state);
        attempt.setResultReason(reason);
        attempts.save(attempt);
    }

    /** Escalates the execution as the engine does after its last retry, with the verification result read next. */
    public static void failExecution(StateManager stateManager, ArtifactStore artifactStore, UUID executionId,
                                     String resultJson, String createdBy) {
        stateManager.transition(executionId, ExecutionStatus.FAILED_ESCALATED,
                Map.of("error", "step exhausted retries"));
        artifactStore.putMarkdown(executionId, VerifyWorker.RESULT, resultJson, createdBy);
    }
}
