package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manual admission against real PostgreSQL: only the selected eligible rows are
 * admitted, a changed row or a stale/incomplete source rejects clearly, and a
 * repeated request resolves to the existing admission instead of new attempts.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotAdmissionIntegrationTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String AUTHORITY = "authority-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        completeSync(AUTHORITY);
    }

    @Test
    void onlySelectedEligibleRowsAreAdmitted() {
        PilotTask selected = task("20001", "MODBAR-101");
        PilotTask unselected = task("20002", "MODBAR-102");
        PilotTask ineligible = task("20003", "MODBAR-103");
        ineligible.setEligibility(PilotTask.Eligibility.INELIGIBLE);
        ineligible.setRoutingReason("no repository mapping for project MODBAR");
        PilotTask ineligibleSaved = tasks.save(ineligible);

        var result = run("run-1", selected, ineligibleSaved);

        assertThat(result.admittedTaskIds()).containsExactly(selected.getId());
        assertThat(result.rejectedReasons()).containsEntry(ineligible.getId(), "task is not eligible: no repository mapping for project MODBAR");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId()))
                .singleElement()
                .satisfies(admitted -> {
                    assertThat(admitted.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(admitted.getKind()).isEqualTo(PilotAttempt.Kind.INITIAL);
                    assertThat(admitted.getRequestId()).isEqualTo("run-1#" + selected.getId());
                    assertThat(admitted.getInputFingerprint()).isEqualTo(PilotAdmissionService.inputFingerprint(selected));
                    assertThat(admitted.getScopeFingerprint()).isEqualTo(AUTHORITY);
                    assertThat(admitted.getDestinationFingerprint())
                            .isEqualTo(PilotAdmissionService.destinationFingerprint(selected));
                    assertThat(admitted.getApprovedSnapshot()).isEqualTo(selected.getIssueSnapshot());
                    assertThat(admitted.getExecutionId()).isNull();
                });
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(unselected.getId())).isEmpty();
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(ineligible.getId())).isEmpty();
    }

    @Test
    void changedRowIsRejectedWithReasonAndStaysSelectable() {
        PilotTask stale = task("20011", "MODBAR-111");
        PilotTask changedInput = task("20012", "MODBAR-112");
        PilotTask changedDestination = task("20013", "MODBAR-113");
        var staleSelection = new PilotCommands.Selection(stale.getId(), stale.getVersion() + 5,
                PilotAdmissionService.inputFingerprint(stale), PilotAdmissionService.destinationFingerprint(stale));
        var changedInputSelection = new PilotCommands.Selection(changedInput.getId(), changedInput.getVersion(),
                "different-input", PilotAdmissionService.destinationFingerprint(changedInput));
        var changedDestinationSelection = new PilotCommands.Selection(changedDestination.getId(),
                changedDestination.getVersion(), PilotAdmissionService.inputFingerprint(changedDestination),
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
    void staleOrIncompleteSyncRejectsWholeBatch() {
        PilotTask first = task("20021", "MODBAR-121");
        PilotTask second = task("20022", "MODBAR-122");
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setAcceptedAuthorityFingerprint(AUTHORITY);
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        var result = run("run-4", first, second);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons()).containsOnlyKeys(first.getId(), second.getId());
        assertThat(result.rejectedReasons().get(first.getId())).contains("sync is incomplete", "search page 2 failed");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void changedSourceAuthorityRejectsWholeBatch() {
        PilotTask first = task("20031", "MODBAR-131");
        PilotTask second = task("20032", "MODBAR-132");
        completeSync("changed-authority");

        var result = run("run-5", first, second);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons()).containsOnlyKeys(first.getId(), second.getId());
        assertThat(result.rejectedReasons().get(first.getId())).contains("changed");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void duplicateRequestResolvesToExistingAdmission() {
        PilotTask selected = task("20041", "MODBAR-141");

        var first = run("run-6", selected);
        var second = run("run-6", selected);

        assertThat(first.admittedTaskIds()).containsExactly(selected.getId());
        assertThat(second.admittedTaskIds()).isEqualTo(first.admittedTaskIds());
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId())).hasSize(1);
    }

    @Test
    void taskWithLiveAdmissionIsRejectedInLaterRun() {
        PilotTask selected = task("20051", "MODBAR-151");
        run("run-7", selected);

        var result = run("run-8", selected);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons().get(selected.getId())).contains("live admission");
        assertThat(attempts.findByTaskIdOrderByOrdinalAsc(selected.getId())).hasSize(1);
    }

    @Test
    void outOfScopeTaskIsNotAdmitted() {
        PilotTask outOfScope = task("20061", "MODBAR-161");
        outOfScope.setInScope(false);
        PilotTask outOfScopeSaved = tasks.save(outOfScope);

        var result = run("run-9", outOfScopeSaved);

        assertThat(result.admittedTaskIds()).isEmpty();
        assertThat(result.rejectedReasons().get(outOfScope.getId())).contains("scope");
        assertThat(attempts.count()).isZero();
    }

    @Test
    void withdrawnTaskCanBeReadmitted() {
        PilotTask selected = task("20071", "MODBAR-171");
        run("run-10", selected);
        PilotAttempt withdrawn = attempts.findByTaskIdOrderByOrdinalAsc(selected.getId()).getFirst();
        withdrawn.setState(PilotAttempt.State.WITHDRAWN);
        withdrawn.setResultReason("task left the pilot scope before start");
        attempts.save(withdrawn);

        var result = run("run-11", selected);

        assertThat(result.admittedTaskIds()).containsExactly(selected.getId());
        List<PilotAttempt> history = attempts.findByTaskIdOrderByOrdinalAsc(selected.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getOrdinal()).isEqualTo(1);
        assertThat(history.get(0).getState()).isEqualTo(PilotAttempt.State.WITHDRAWN);
        assertThat(history.get(1).getOrdinal()).isEqualTo(2);
        assertThat(history.get(1).getState()).isEqualTo(PilotAttempt.State.RESERVED);
        assertThat(attempts.findLiveByTaskId(selected.getId())).hasSize(1);
    }

    @Test
    void invalidRequestIdIsRejectedWithoutCreatingAttempts() {
        PilotTask selected = task("20081", "MODBAR-181");

        assertThatThrownBy(() -> run(" ", selected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request id is required");
        assertThatThrownBy(() -> run("r".repeat(64), selected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 63 characters");

        assertThat(attempts.count()).isZero();
    }

    private PilotAdmissionService.AdmissionResult run(String requestId, PilotTask... selectedTasks) {
        return run(requestId, Arrays.stream(selectedTasks).map(this::selectionFor).toList());
    }

    private PilotAdmissionService.AdmissionResult run(String requestId, PilotCommands.Selection... selections) {
        return run(requestId, List.of(selections));
    }

    private PilotAdmissionService.AdmissionResult run(String requestId, List<PilotCommands.Selection> selections) {
        return admissions.runSelected(new PilotCommands.RunSelected(requestId, AUTHORITY, selections));
    }

    private void completeSync(String authority) {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setAcceptedAuthorityFingerprint(authority);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installations.save(installation);
    }

    private PilotTask task(String issueId, String displayKey) {
        return tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey, "MODBAR",
                "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
    }

    private PilotCommands.Selection selectionFor(PilotTask task) {
        return new PilotCommands.Selection(task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task), PilotAdmissionService.destinationFingerprint(task));
    }
}
