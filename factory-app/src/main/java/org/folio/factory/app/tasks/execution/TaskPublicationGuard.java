package org.folio.factory.app.tasks.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.folio.factory.app.tasks.admission.TaskAdmissionChecks;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
@Slf4j
public class TaskPublicationGuard {

    private final TaskAttemptRepository attempts;
    private final TaskRepository tasks;

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
        TaskAttempt attempt = attempts.findByExecutionId(executionId)
                .orElseThrow(() -> blocked("the task attempt bound to the execution is missing"));
        Task task = tasks.findById(attempt.getTaskId())
                .orElseThrow(() -> blocked("the task behind the attempt is missing"));
        requireApprovedAdmission(consumedRequest, attempt, task);
        requireValidDestination(consumedRequest, destination);
    }

    /** The request, the task's routing and its destination are still what the developer approved. */
    private static void requireApprovedAdmission(CodingRequest consumedRequest, TaskAttempt attempt, Task task) {
        String approvedKey = Objects.toString(attempt.approvedSnapshotMap().get("key"), "");
        if (!consumedRequest.issueKey().equals(approvedKey)) {
            throw blocked("the consumed issue identity no longer matches the approved admission");
        }
        String repositoryKey = TaskRoutingReason.eligibleRepositoryKey(task.getRoutingReason());
        if (repositoryKey == null || !repositoryKey.equals(consumedRequest.repository().key())) {
            throw blocked("the consumed repository no longer matches the admitted routing");
        }
        if (task.getEligibility() != Task.Eligibility.ELIGIBLE) {
            throw blocked("the task is no longer eligible");
        }
        if (!attempt.getDestinationFingerprint().equals(TaskAdmissionChecks.destinationFingerprint(task))) {
            throw blocked("the task's destination routing changed after admission");
        }
    }

    private static void requireValidDestination(CodingRequest consumedRequest, DeliveryTarget destination) {
        if (destination == null) {
            throw blocked("the resolved delivery destination is missing");
        }
        destination.requireValid();
        if (!destination.baseBranch().equals(consumedRequest.repository().baseBranch())) {
            throw blocked("the delivery base branch does not match the base the candidate was"
                    + " prepared on");
        }
    }

    private static DeliveryBlockedException blocked(String reason) {
        return new DeliveryBlockedException(
                "Publication authority recheck failed: " + reason
                        + "; the candidate is preserved unpublished");
    }
}
