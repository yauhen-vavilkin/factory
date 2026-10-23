package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Admission-mode transitions and source-authority acceptance. Enabling
 * AUTOMATIC is an explicit scope/destination enable action: it must affirm the
 * currently observed authority fingerprint, accepts it, and delegates current
 * eligible tasks. A complete sync that observes a changed authority returns an
 * automatic installation to REVIEW_BEFORE_RUN and invalidates queued
 * approvals; the observed fingerprint is kept for display until the operator
 * re-enables against it. Returning to manual never touches queued or running
 * work.
 */
@Service
public class PilotModeService {

    private static final Logger log = LoggerFactory.getLogger(PilotModeService.class);

    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotAutomaticAdmission automaticAdmission;
    private final TransactionTemplate transactions;

    public PilotModeService(PilotAttemptRepository attempts, PilotInstallationRepository installations,
                            PilotAutomaticAdmission automaticAdmission,
                            PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.installations = installations;
        this.automaticAdmission = automaticAdmission;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public enum AdmissionMode {
        REVIEW_BEFORE_RUN, AUTOMATIC
    }

    public record ModeResult(AdmissionMode mode, int admittedQueueCount, String explanation) {
    }

    public ModeResult setMode(AdmissionMode mode, String expectedAuthorityFingerprint) {
        if (mode == null) {
            throw new IllegalArgumentException("An admission mode is required");
        }
        if (mode == AdmissionMode.REVIEW_BEFORE_RUN) {
            return disable();
        }
        return enable(expectedAuthorityFingerprint);
    }

    /**
     * Called by discovery inside the transaction that records a complete sync's
     * observed authority, so a change can never leave automatic mode enabled
     * against stale accepted state.
     */
    public void revertIfAuthorityChanged(PilotInstallation installation) {
        String accepted = installation.getAcceptedAuthorityFingerprint();
        if (installation.getMode() != PilotInstallation.Mode.AUTOMATIC
                || accepted == null
                || accepted.equals(installation.getObservedAuthorityFingerprint())) {
            return;
        }
        int invalidated = invalidateQueuedApprovals(accepted);
        installation.setMode(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
        log.info("Pilot source authority changed; automatic admission stopped and {} queued"
                + " approval(s) invalidated", invalidated);
    }

    private ModeResult disable() {
        transactions.executeWithoutResult(status -> installations.lockById(1).ifPresent(installation -> {
            installation.setMode(PilotInstallation.Mode.REVIEW_BEFORE_RUN);
            installations.save(installation);
        }));
        return new ModeResult(AdmissionMode.REVIEW_BEFORE_RUN, 0,
                "Manual admission restored; already queued and running work stays delegated");
    }

    private ModeResult enable(String expectedAuthorityFingerprint) {
        if (expectedAuthorityFingerprint == null || expectedAuthorityFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "Enabling automatic admission requires the observed pilot source authority fingerprint");
        }
        int invalidated = transactions.execute(status ->
                enableLocked(expectedAuthorityFingerprint.strip()));
        PilotAdmissionService.AdmissionResult admitted = automaticAdmission.admitEligible();
        return new ModeResult(AdmissionMode.AUTOMATIC, admitted.admittedTaskIds().size(),
                "Automatic admission enabled for the current pilot source authority; "
                        + admitted.admittedTaskIds().size() + " eligible task(s) delegated"
                        + (invalidated > 0
                                ? "; " + invalidated + " queued approval(s) from the previous authority invalidated"
                                : ""));
    }

    private int enableLocked(String expectedAuthorityFingerprint) {
        PilotInstallation installation = installations.lockById(1)
                .orElseGet(PilotInstallation::initial);
        if (!installation.isSyncComplete() || installation.getSyncProblem() != null) {
            throw new IllegalArgumentException("Pilot source sync is incomplete"
                    + (installation.getSyncProblem() == null ? "" : ": " + installation.getSyncProblem())
                    + "; refresh discovery before enabling automatic admission");
        }
        String observed = installation.getObservedAuthorityFingerprint();
        if (observed == null || !observed.equals(expectedAuthorityFingerprint)) {
            throw new IllegalArgumentException("The pilot source authority changed; refresh discovery"
                    + " and enable automatic admission with the current fingerprint");
        }
        String accepted = installation.getAcceptedAuthorityFingerprint();
        int invalidated = accepted == null || accepted.equals(observed)
                ? 0
                : invalidateQueuedApprovals(accepted);
        installation.setMode(PilotInstallation.Mode.AUTOMATIC);
        installation.setAcceptedAuthorityFingerprint(observed);
        installations.save(installation);
        return invalidated;
    }

    private int invalidateQueuedApprovals(String approvedScopeFingerprint) {
        List<PilotAttempt> stale = attempts.findByStateAndScopeFingerprint(
                PilotAttempt.State.RESERVED, approvedScopeFingerprint);
        for (PilotAttempt attempt : stale) {
            if (attempt.getKind() == PilotAttempt.Kind.RETRY) {
                attempt.setState(PilotAttempt.State.STOPPED);
                attempt.setResultReason("pilot source authority changed after admission; the approved"
                        + " retry no longer matches; run a fresh explicit Retry");
            } else {
                attempt.setState(PilotAttempt.State.WITHDRAWN);
                attempt.setResultReason("pilot source authority changed after admission; select the"
                        + " task again to delegate it");
            }
            attempts.save(attempt);
        }
        return stale.size();
    }
}
