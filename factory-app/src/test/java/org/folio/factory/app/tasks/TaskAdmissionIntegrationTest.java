package org.folio.factory.app.tasks;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.admission.TaskCommands;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;

/**
 * Manual admission against real PostgreSQL: only the selected eligible rows are
 * admitted, a changed row or a token without push access rejects clearly before
 * anything is queued, a failed scan does not block the tasks already shown, and
 * a repeated request resolves to the existing admission instead of new attempts.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class TaskAdmissionIntegrationTest {

    private static final String INSTANCE = "jira.example.org";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    TaskInstallationRepository installations;

    @Autowired
    TaskAdmissionService admissions;

    @MockitoBean
    DeveloperPreflight preflight;

    @BeforeEach
    void resetTaskState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        installations.save(TaskInstallations.seeded());
        completeSync();
    }

    @Test
    void onlySelectedEligibleRowsAreAdmitted() {
        Task selected = task("20001", "MODBAR-101");
        Task unselected = task("20002", "MODBAR-102");
        Task ineligible = task("20003", "MODBAR-103");
        ineligible.setEligibility(Task.Eligibility.INELIGIBLE);
        ineligible.setRoutingReason("no repository mapping for project MODBAR");
        Task ineligibleSaved = tasks.save(ineligible);

        var result = run("run-1", selected, ineligibleSaved);

        assertThat(result.admittedTaskIds()).containsExactly(selected.getId());
        assertThat(result.rejectedReasons()).containsEntry(ineligible.getId(), "task is not eligible: no repository mapping for project MODBAR");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId()))
                .singleElement()
                .satisfies(admitted -> {
                    assertThat(admitted.getState()).isEqualTo(TaskAttempt.State.RESERVED);
                    assertThat(admitted.getKind()).isEqualTo(TaskAttempt.Kind.INITIAL);
                    assertThat(admitted.getRequestId()).isEqualTo("run-1#" + selected.getId());
                    assertThat(admitted.getInputFingerprint()).isEqualTo(TaskAdmissionChecks.inputFingerprint(selected));
                    assertThat(admitted.getAdmittedVia()).isEqualTo(TaskAttempt.AdmittedVia.MANUAL);
                    assertThat(admitted.getDestinationFingerprint())
                            .isEqualTo(TaskAdmissionChecks.destinationFingerprint(selected));
                    assertThat(admitted.getApprovedSnapshot()).isEqualTo(selected.getIssueSnapshot());
                    assertThat(admitted.getExecutionId()).isNull();
                });
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(unselected.getId())).isEmpty();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(ineligible.getId())).isEmpty();
    }

    @Test
    void changedRowIsRejectedWithReasonAndStaysSelectable() {
        Task stale = task("20011", "MODBAR-111");
        Task changedInput = task("20012", "MODBAR-112");
        Task changedDestination = task("20013", "MODBAR-113");
        var staleSelection = new TaskCommands.Selection(stale.getId(), stale.getVersion() + 5,
                TaskAdmissionChecks.inputFingerprint(stale), TaskAdmissionChecks.destinationFingerprint(stale));
        var changedInputSelection = new TaskCommands.Selection(changedInput.getId(), changedInput.getVersion(),
                "different-input", TaskAdmissionChecks.destinationFingerprint(changedInput));
        var changedDestinationSelection = new TaskCommands.Selection(changedDestination.getId(),
                changedDestination.getVersion(), TaskAdmissionChecks.inputFingerprint(changedDestination),
                "different-destination");

        var result = run("run-2", staleSelection, changedInputSelection, changedDestinationSelection);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons()).hasSize(3);
        assertThat(result.rejectedReasons().get(stale.getId())).contains("changed");
        assertThat(result.rejectedReasons().get(changedInput.getId())).contains("changed");
        assertThat(result.rejectedReasons().get(changedDestination.getId())).contains("changed");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(stale.getId())).isEmpty();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(changedInput.getId())).isEmpty();

        var readmission = run("run-3", stale);
        assertThat(readmission.admittedTaskIds()).containsExactly(stale.getId());
    }

    @Test
    void failedScanDoesNotBlockRunningTheTasksAlreadyShown() {
        Task first = task("20021", "MODBAR-121");
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        var result = run("run-4", first);

        assertThat(result.admittedTaskIds()).containsExactly(first.getId());
        assertThat(attempts.findLiveByTaskId(first.getId())).hasSize(1);
    }

    @Test
    void tokenWithoutPushAccessRejectsTheTaskBeforeAnythingIsQueued() {
        Task first = task("20031", "MODBAR-131");
        Task second = task("20032", "MODBAR-132");
        doThrow(new IllegalStateException("Your GitHub token cannot push to folio-org/mod-bar"))
                .when(preflight).requirePushAccess("mod-bar");

        var result = run("run-5", first, second);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons()).containsOnlyKeys(first.getId(), second.getId());
        assertThat(result.rejectedReasons().get(first.getId()))
                .isEqualTo("Your GitHub token cannot push to folio-org/mod-bar");
        verify(preflight, times(1)).requirePushAccess("mod-bar");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void duplicateRequestResolvesToExistingAdmission() {
        Task selected = task("20041", "MODBAR-141");

        var first = run("run-6", selected);
        var second = run("run-6", selected);

        assertThat(first.admittedTaskIds()).containsExactly(selected.getId());
        assertThat(second.admittedTaskIds()).isEqualTo(first.admittedTaskIds());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId())).hasSize(1);
    }

    @Test
    void taskWithLiveAdmissionIsRejectedInLaterRun() {
        Task selected = task("20051", "MODBAR-151");
        run("run-7", selected);

        var result = run("run-8", selected);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons().get(selected.getId())).contains("live admission");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId())).hasSize(1);
    }

    @Test
    void outOfScopeTaskIsNotAdmitted() {
        Task outOfScope = task("20061", "MODBAR-161");
        outOfScope.setInScope(false);
        Task outOfScopeSaved = tasks.save(outOfScope);

        var result = run("run-9", outOfScopeSaved);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons().get(outOfScope.getId())).contains("no longer assigned to you");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void withdrawnTaskCanBeReadmitted() {
        Task selected = task("20071", "MODBAR-171");
        run("run-10", selected);
        TaskAttempt withdrawn = attempts.findByTaskIdOrderByOrdinalAsc(selected.getId()).getFirst();
        withdrawn.setState(TaskAttempt.State.WITHDRAWN);
        withdrawn.setResultReason("withdrawn before start");
        attempts.save(withdrawn);

        var result = run("run-11", selected);

        assertThat(result.admittedTaskIds()).containsExactly(selected.getId());
        List<TaskAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(selected.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getOrdinal()).isEqualTo(1);
        assertThat(history.get(0).getState()).isEqualTo(TaskAttempt.State.WITHDRAWN);
        assertThat(history.get(1).getOrdinal()).isEqualTo(2);
        assertThat(history.get(1).getState()).isEqualTo(TaskAttempt.State.RESERVED);
        assertThat(attempts.findLiveByTaskId(selected.getId())).hasSize(1);
    }

    @Test
    void taskWithConsumedInitialAttemptIsNotReselectable() {
        Task selected = task("20091", "MODBAR-191");
        run("run-12", selected);
        TaskAttempt consumed = attempts.findByTaskIdOrderByOrdinalAsc(selected.getId()).getFirst();
        consumed.setState(TaskAttempt.State.STOPPED);
        consumed.setResultReason("coding: verification failed");
        attempts.save(consumed);

        var result = run("run-13", selected);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons().get(selected.getId())).contains("already has an attempt");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId())).hasSize(1);
    }

    @Test
    void invalidRequestIdIsRejectedWithoutCreatingAttempts() {
        Task selected = task("20081", "MODBAR-181");

        assertThatThrownBy(() -> run(" ", selected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id is required");
        assertThatThrownBy(() -> run("r".repeat(64), selected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 63 characters");

        assertThat(attempts.count()).isZero();
    }

    private TaskAdmissionService.AdmissionResult run(String requestId, Task... selectedTasks) {
        return run(requestId, Arrays.stream(selectedTasks).map(this::selectionFor).toList());
    }

    private TaskAdmissionService.AdmissionResult run(String requestId, TaskCommands.Selection... selections) {
        return run(requestId, List.of(selections));
    }

    private TaskAdmissionService.AdmissionResult run(String requestId, List<TaskCommands.Selection> selections) {
        return admissions.runSelected(new TaskCommands.RunSelected(requestId, selections, null));
    }

    private void completeSync() {
        TaskInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
    }

    private Task task(String issueId, String displayKey) {
        return tasks.save(new Task(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                Task.Eligibility.ELIGIBLE, "repository mod-bar"));
    }

    private TaskCommands.Selection selectionFor(Task task) {
        return new TaskCommands.Selection(task.getId(), task.getVersion(),
                TaskAdmissionChecks.inputFingerprint(task), TaskAdmissionChecks.destinationFingerprint(task));
    }
}
