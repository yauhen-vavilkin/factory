package org.folio.factory.app.pilot.admission;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manual admission of a selected task subset into the durable pilot queue. The
 * request id and global source health are checked without locking; each selected
 * row is then admitted in its own short transaction that locks the task,
 * revalidates the selection against the current row and reserves one live
 * attempt. A repeated request id resolves to the existing admission instead of
 * creating anything. Admission never creates engine executions.
 */
@Service
public class PilotAdmissionService {

    /**
     * Attempt request ids are the run id plus the task id, so the run id must
     * leave room for both within pilot_attempt.request_id (100 characters).
     */
    static final int MAX_REQUEST_ID_LENGTH = 63;

    private final PilotTaskRepository tasks;
    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final TransactionTemplate transactions;

    public PilotAdmissionService(PilotTaskRepository tasks, PilotAttemptRepository attempts,
                                 PilotInstallationRepository installations,
                                 PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.attempts = attempts;
        this.installations = installations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record AdmissionResult(List<UUID> admittedTaskIds, Map<UUID, String> rejectedReasons) {
        public AdmissionResult {
            admittedTaskIds = List.copyOf(admittedTaskIds);
            rejectedReasons = Map.copyOf(rejectedReasons);
        }
    }

    public AdmissionResult runSelected(PilotCommands.RunSelected run) {
        String requestId = requireRequestId(run.requestId());
        List<UUID> admitted = new ArrayList<>();
        Map<UUID, String> rejected = new LinkedHashMap<>();
        List<PilotCommands.Selection> fresh = new ArrayList<>();
        for (PilotCommands.Selection selection : run.selections()) {
            if (attempts.findByRequestId(attemptRequestId(requestId, selection.taskId())).isPresent()) {
                admitted.add(selection.taskId());
            } else {
                fresh.add(selection);
            }
        }
        if (!fresh.isEmpty()) {
            String batchProblem = batchProblem(run.expectedAuthorityFingerprint());
            if (batchProblem != null) {
                fresh.forEach(selection -> rejected.put(selection.taskId(), batchProblem));
            } else {
                admitEach(requestId, fresh, admitted, rejected);
            }
        }
        return new AdmissionResult(admitted, rejected);
    }

    private void admitEach(String requestId, List<PilotCommands.Selection> selections, List<UUID> admitted,
                           Map<UUID, String> rejected) {
        for (PilotCommands.Selection selection : selections) {
            String reason = admitSelection(requestId, selection);
            if (reason == null) {
                admitted.add(selection.taskId());
            } else {
                rejected.put(selection.taskId(), reason);
            }
        }
    }

    private String admitSelection(String requestId, PilotCommands.Selection selection) {
        try {
            return transactions.execute(status -> admitOnce(requestId, selection));
        } catch (DataIntegrityViolationException e) {
            // A concurrent admission won a unique constraint. When it holds our
            // request id, resolve to that stored admission; otherwise the task's
            // live slot was taken concurrently.
            if (attempts.findByRequestId(attemptRequestId(requestId, selection.taskId())).isPresent()) {
                return null;
            }
            return "task already has a live admission";
        }
    }

    /**
     * @return null when the attempt was reserved, otherwise the rejection reason
     */
    private String admitOnce(String requestId, PilotCommands.Selection selection) {
        PilotTask task = tasks.lockById(selection.taskId()).orElse(null);
        if (task == null) {
            return "task " + selection.taskId() + " no longer exists";
        }
        if (task.getVersion() != selection.expectedVersion()
                || !matches(selection.inputFingerprint(), inputFingerprint(task))
                || !matches(selection.destinationFingerprint(), destinationFingerprint(task))) {
            return "task changed since selection; review and select again";
        }
        if (task.getEligibility() != PilotTask.Eligibility.ELIGIBLE) {
            return "task is not eligible: " + task.getRoutingReason();
        }
        if (!task.isInScope()) {
            return "task is outside the current pilot scope; refresh discovery and select again";
        }
        if (!attempts.findLiveByTaskId(task.getId()).isEmpty()) {
            return "task already has a live admission";
        }
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        PilotAttempt attempt = new PilotAttempt(task.getId(), nextOrdinal(task.getId()),
                PilotAttempt.Kind.INITIAL, attemptRequestId(requestId, task.getId()), PilotAttempt.State.RESERVED);
        attempt.setInputFingerprint(selection.inputFingerprint());
        attempt.setScopeFingerprint(installation.getAcceptedAuthorityFingerprint());
        attempt.setDestinationFingerprint(selection.destinationFingerprint());
        attempt.setApprovedSnapshot(task.getIssueSnapshot());
        attempts.save(attempt);
        return null;
    }

    private String batchProblem(String expectedAuthorityFingerprint) {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        if (!installation.isSyncComplete() || installation.getSyncProblem() != null) {
            return "pilot source sync is incomplete"
                    + (installation.getSyncProblem() == null ? "" : ": " + installation.getSyncProblem());
        }
        if (!matches(expectedAuthorityFingerprint, installation.getAcceptedAuthorityFingerprint())) {
            return "pilot source changed since selection; review and select again";
        }
        return null;
    }

    private int nextOrdinal(UUID taskId) {
        return attempts.findByTaskIdOrderByOrdinalAsc(taskId).stream()
                .mapToInt(PilotAttempt::getOrdinal)
                .max()
                .orElse(0) + 1;
    }

    static String attemptRequestId(String requestId, UUID taskId) {
        return requestId + "#" + taskId;
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("A request id is required to admit selected tasks");
        }
        String trimmed = requestId.strip();
        if (trimmed.length() > MAX_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Request id must not exceed " + MAX_REQUEST_ID_LENGTH + " characters");
        }
        return trimmed;
    }

    private static boolean matches(String expected, String current) {
        return expected != null && expected.equals(current);
    }

    /** Identity of the task's bounded intake view; source revision is the snapshot hash. */
    public static String inputFingerprint(PilotTask task) {
        return task.getSourceRevision();
    }

    /** Identity of the task's routing decision; a re-mapped project yields a different value. */
    public static String destinationFingerprint(PilotTask task) {
        return sha256Hex(task.getRoutingReason());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
