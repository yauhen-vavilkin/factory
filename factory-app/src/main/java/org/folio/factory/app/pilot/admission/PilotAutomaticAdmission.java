package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotJiraInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Automatic delegation of discovered tasks in explicit AUTOMATIC mode. It
 * selects eligible in-scope tasks whose initial attempt was never consumed —
 * never attempted, or only withdrawn before start — and routes them through the
 * same locked admission path and idempotency as manual selection; there is no
 * second queue. Attempted tasks stay out until an explicit Retry, and a stale
 * or partial discovery blocks new admissions.
 */
@Component
public class PilotAutomaticAdmission {

    private static final Logger log = LoggerFactory.getLogger(PilotAutomaticAdmission.class);

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotAdmissionService admissions;
    private final PilotJiraInstance jiraInstance;

    public PilotAutomaticAdmission(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                                   PilotInstallationRepository installations,
                                   PilotAdmissionService admissions, PilotJiraInstance jiraInstance) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.admissions = admissions;
        this.jiraInstance = jiraInstance;
    }

    public PilotAdmissionService.AdmissionResult admitEligible() {
        PilotInstallation installation = installations.findById(1).orElse(null);
        if (installation == null
                || installation.getMode() != PilotInstallation.Mode.AUTOMATIC
                || !installation.isSyncComplete()
                || installation.getSyncProblem() != null) {
            return empty();
        }
        String authority = installation.getAcceptedAuthorityFingerprint();
        if (authority == null) {
            return empty();
        }
        List<PilotCommands.Selection> selections = tasks
                .findByJiraInstanceAndInScopeTrueAndEligibility(
                        jiraInstance.current(), PilotTask.Eligibility.ELIGIBLE)
                .stream()
                .filter(task -> attempts.findByTaskIdOrderByOrdinalAsc(task.getId()).stream()
                        .allMatch(attempt -> attempt.getState() == PilotAttempt.State.WITHDRAWN))
                .map(this::selectionFor)
                .toList();
        if (selections.isEmpty()) {
            return empty();
        }
        PilotAdmissionService.AdmissionResult result = admissions.runSelected(
                new PilotCommands.RunSelected("auto-" + UUID.randomUUID(), authority, selections),
                PilotAttempt.AdmittedVia.AUTOMATIC);
        if (!result.admittedTaskIds().isEmpty()) {
            log.info("Automatic pilot admission delegated {} eligible task(s)", result.admittedTaskIds().size());
        }
        return result;
    }

    private PilotCommands.Selection selectionFor(PilotTask task) {
        return new PilotCommands.Selection(task.getId(), task.getVersion(),
                PilotAdmissionService.inputFingerprint(task),
                PilotAdmissionService.destinationFingerprint(task));
    }

    private static PilotAdmissionService.AdmissionResult empty() {
        return new PilotAdmissionService.AdmissionResult(List.of(), Map.of());
    }
}
