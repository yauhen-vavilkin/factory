package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotRoutingReason;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rechecks current publication authority immediately before a remote mutation:
 * the consumed request must still be the approved issue and repository, the
 * task's routing must be unchanged, and the destination must still be a valid
 * operator configuration. A mismatch or an unavailable recheck stops
 * publication and never falls back to another destination. Jira changes after
 * the click (status, assignee, comments) do not stop it: the run works on the
 * snapshot approved at the click.
 */
@Component
public class PilotPublicationGuard {

    private static final Logger log = LoggerFactory.getLogger(PilotPublicationGuard.class);

    private final PilotAttemptRepository attempts;
    private final PilotTaskRepository tasks;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotPublicationGuard(PilotAttemptRepository attempts, PilotTaskRepository tasks) {
        this.attempts = attempts;
        this.tasks = tasks;
    }

    public void requireCurrent(UUID executionId, CodingRequest consumedRequest, DeliveryTarget destination) {
        try {
            requireCurrentOrThrow(executionId, consumedRequest, destination);
        } catch (DeliveryBlockedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Publication authority could not be rechecked for execution {}", executionId, e);
            throw new DeliveryBlockedException(
                    "Publication authority could not be rechecked; inspect the attempt before"
                            + " publishing", e);
        }
    }

    private void requireCurrentOrThrow(UUID executionId, CodingRequest consumedRequest,
                                       DeliveryTarget destination) {
        if (consumedRequest == null || consumedRequest.repository() == null
                || consumedRequest.issueKey().isBlank()) {
            throw blocked("the consumed coding request is missing or not normalized");
        }
        PilotAttempt attempt = attempts.findByExecutionId(executionId)
                .orElseThrow(() -> blocked("the pilot attempt bound to the execution is missing"));
        PilotTask task = tasks.findById(attempt.getTaskId())
                .orElseThrow(() -> blocked("the pilot task behind the attempt is missing"));
        String approvedKey = Objects.toString(approvedSnapshot(attempt).get("key"), "");
        if (!consumedRequest.issueKey().equals(approvedKey)) {
            throw blocked("the consumed issue identity no longer matches the approved admission");
        }
        String repositoryKey = PilotRoutingReason.eligibleRepositoryKey(task.getRoutingReason());
        if (repositoryKey == null || !repositoryKey.equals(consumedRequest.repository().key())) {
            throw blocked("the consumed repository no longer matches the admitted routing");
        }
        if (task.getEligibility() != PilotTask.Eligibility.ELIGIBLE) {
            throw blocked("the task is no longer eligible");
        }
        if (!attempt.getDestinationFingerprint().equals(PilotAdmissionService.destinationFingerprint(task))) {
            throw blocked("the task's destination routing changed after admission");
        }
        if (destination == null) {
            throw blocked("the resolved delivery destination is missing");
        }
        destination.requireValid();
        if (!destination.baseBranch().equals(consumedRequest.repository().baseBranch())) {
            throw blocked("the delivery base branch does not match the base the candidate was"
                    + " prepared on");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> approvedSnapshot(PilotAttempt attempt) {
        String snapshot = attempt.getApprovedSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }
        return json.readValue(snapshot, Map.class);
    }

    private static DeliveryBlockedException blocked(String reason) {
        return new DeliveryBlockedException(
                "Publication authority recheck failed: " + reason
                        + "; the candidate is preserved unpublished");
    }
}
