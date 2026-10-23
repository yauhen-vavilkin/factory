package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.devfactory.pilot.PilotStops;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves one pilot attempt into an understandable outcome and decides the
 * installation's active slot: a task-specific terminal outcome with proven
 * workload inactivity releases the slot, a shared provider/auth/environment
 * failure holds it as one installation pause, and any unproven cleanup or
 * unresolved publication identity holds it for inspection. Engine COMPLETED
 * alone is never PR_READY — the delivered pull-request identity is the
 * evidence. Only this service releases installation.activeAttemptId.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot.execution", name = "enabled", havingValue = "true")
public class PilotOutcomeService {

    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifacts;
    private final DockerWorkloads docker;
    private final FlowRegistry flows;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotOutcomeService(PilotAttemptRepository attempts,
                               PilotInstallationRepository installations,
                               PipelineExecutionRepository executions,
                               ArtifactStore artifacts,
                               DockerWorkloads docker,
                               FlowRegistry flows,
                               PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.installations = installations;
        this.executions = executions;
        this.artifacts = artifacts;
        this.docker = docker;
        this.flows = flows;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record AttemptOutcome(State state, String stage, String reason, String publicationIdentity,
                                 boolean workloadInactive, String uncertainty) {

        public enum State { PROCESSING, PR_READY, STOPPED, NEEDS_INSPECTION, PROCESSING_PAUSED }

        /** Only released outcomes let the dispatcher start the next task. */
        public boolean released() {
            return state == State.PR_READY || state == State.STOPPED;
        }
    }

    private record Classification(AttemptOutcome.State state, String stage, String reason,
                                  String publicationIdentity, boolean shared) {
    }

    public AttemptOutcome refresh(UUID attemptId) {
        PilotAttempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new NoSuchElementException("No pilot attempt " + attemptId));
        if (attempt.getState() == PilotAttempt.State.WITHDRAWN
                || attempt.getState() == PilotAttempt.State.PR_READY
                || attempt.getState() == PilotAttempt.State.STOPPED) {
            return new AttemptOutcome(attempt.getState() == PilotAttempt.State.PR_READY
                    ? AttemptOutcome.State.PR_READY : AttemptOutcome.State.STOPPED,
                    null, attempt.getResultReason(), attempt.getPublicationIdentity(), true, null);
        }
        UUID executionId = attempt.getExecutionId();
        if (executionId == null) {
            return processing(attempt);
        }
        Optional<PipelineExecution> execution = executions.findById(executionId);
        if (execution.isEmpty()) {
            return hold(attempt, "the bound engine execution record is missing");
        }
        AttemptOutcome delivered = deliveredOutcome(attempt, executionId);
        if (delivered != null) {
            return delivered;
        }
        if (!execution.get().getStatus().isTerminal()) {
            return processing(attempt);
        }
        return resolveTerminal(attempt, executionId, execution.get().getStatus());
    }

    /**
     * A delivered pull request is the one outcome that releases the slot before
     * the execution finishes: PR feedback and merge never hold the slot. Nothing
     * else short-circuits a still-running execution.
     */
    private AttemptOutcome deliveredOutcome(PilotAttempt attempt, UUID executionId) {
        JsonNode delivery = readArtifact(executionId, DeliveryWorker.DELIVERY);
        if (!"DELIVERED".equals(delivery.path("state").asString(""))) {
            return null;
        }
        String pullRequestUrl = delivery.path("pullRequestUrl").asString("");
        if (pullRequestUrl.isBlank()) {
            return null;
        }
        String uncertainty = proveInactive(executionId);
        if (uncertainty != null) {
            return hold(attempt, uncertainty);
        }
        return release(attempt, new Classification(AttemptOutcome.State.PR_READY, "publish",
                attempt.getResultReason(), pullRequestUrl, false));
    }

    private AttemptOutcome resolveTerminal(PilotAttempt attempt, UUID executionId,
                                           ExecutionStatus status) {
        String uncertainty = proveInactive(executionId);
        Classification classification = classify(attempt, executionId, status);
        if (uncertainty != null) {
            return hold(attempt, uncertainty);
        }
        if (classification.shared()) {
            return holdShared(attempt, classification);
        }
        if (classification.state() == AttemptOutcome.State.NEEDS_INSPECTION) {
            return hold(attempt, classification.reason());
        }
        return release(attempt, classification);
    }

    /** Strict owned-cleanup evidence; null only when every owned workload is gone. */
    private String proveInactive(UUID executionId) {
        try {
            for (var step : flows.require(PilotExecutionLauncher.FLOW_ID).agentChain()) {
                docker.cleanOwned(executionId, step.stepId());
            }
            docker.cleanOwnedExecution(executionId);
            return null;
        } catch (RuntimeException e) {
            return "workload inactivity could not be confirmed: " + e.getMessage();
        }
    }

    private Classification classify(PilotAttempt attempt, UUID executionId, ExecutionStatus status) {
        JsonNode delivery = readArtifact(executionId, DeliveryWorker.DELIVERY);
        String deliveryState = delivery.path("state").asString("");
        String pullRequestUrl = delivery.path("pullRequestUrl").asString("");
        if ("DELIVERED".equals(deliveryState)) {
            if (pullRequestUrl.isBlank()) {
                return new Classification(AttemptOutcome.State.NEEDS_INSPECTION, "publish",
                        "the candidate was delivered without a known pull-request identity",
                        null, false);
            }
            return new Classification(AttemptOutcome.State.PR_READY, "publish",
                    attempt.getResultReason(), pullRequestUrl, false);
        }
        JsonNode result = readArtifact(executionId, VerifyWorker.RESULT);
        String resultState = result.path("state").asString("");
        String resultReason = result.path("reason").asString("");
        String codingFailureCode = readArtifact(executionId, DevelopWorker.OUTCOME)
                .path("failure").path("code").asString(null);
        String reason = firstNonBlank(attempt.getResultReason(),
                resultReason.isBlank() ? "" : resultState + ": " + resultReason,
                "engine execution ended in status " + status);
        String stage = stageOf(attempt.getResultReason(), resultState);
        return new Classification(AttemptOutcome.State.STOPPED, stage, reason, null,
                PilotStops.sharedFailure(reason, codingFailureCode));
    }

    private AttemptOutcome release(PilotAttempt attempt, Classification classification) {
        try {
            transactions.executeWithoutResult(status -> releaseLocked(attempt.getId(), classification));
        } catch (OptimisticLockingFailureException e) {
            // A concurrent scheduler or dispatcher refresh saved the attempt first:
            // re-read and release only while the outcome is still applicable.
            transactions.executeWithoutResult(status -> attempts.findById(attempt.getId())
                    .filter(current -> current.getState().isLive()
                            && current.getState() != PilotAttempt.State.NEEDS_INSPECTION)
                    .ifPresent(current -> releaseLocked(attempt.getId(), classification)));
        }
        return new AttemptOutcome(classification.state(), classification.stage(),
                classification.reason(), classification.publicationIdentity(), true, null);
    }

    private void releaseLocked(UUID attemptId, Classification classification) {
        PilotAttempt current = attempts.findById(attemptId).orElseThrow();
        current.setState(PilotAttempt.State.valueOf(classification.state().name()));
        if (classification.publicationIdentity() != null) {
            current.setPublicationIdentity(classification.publicationIdentity());
        }
        if (current.getResultReason() == null && classification.reason() != null
                && !classification.reason().isBlank()) {
            current.setResultReason(capReason(classification.reason()));
        }
        attempts.save(current);
        installations.lockById(1).ifPresent(installation -> {
            if (attemptId.equals(installation.getActiveAttemptId())) {
                installation.setActiveAttemptId(null);
                installations.save(installation);
            }
        });
    }

    private AttemptOutcome holdShared(PilotAttempt attempt, Classification classification) {
        markNeedsInspection(attempt, classification.reason());
        return new AttemptOutcome(AttemptOutcome.State.PROCESSING_PAUSED, classification.stage(),
                classification.reason(), classification.publicationIdentity(), true, null);
    }

    private AttemptOutcome hold(PilotAttempt attempt, String uncertainty) {
        markNeedsInspection(attempt, null);
        return new AttemptOutcome(AttemptOutcome.State.NEEDS_INSPECTION, null,
                attempts.findById(attempt.getId()).map(PilotAttempt::getResultReason).orElse(null),
                attempt.getPublicationIdentity(), false, uncertainty);
    }

    private void markNeedsInspection(PilotAttempt attempt, String cause) {
        transactions.executeWithoutResult(status -> attempts.findById(attempt.getId())
                .filter(current -> current.getState().isLive()
                        && current.getState() != PilotAttempt.State.NEEDS_INSPECTION)
                .ifPresent(current -> {
                    current.setState(PilotAttempt.State.NEEDS_INSPECTION);
                    if ((current.getResultReason() == null || current.getResultReason().isBlank())
                            && cause != null && !cause.isBlank()) {
                        current.setResultReason(capReason(cause));
                    }
                    attempts.save(current);
                }));
    }

    private AttemptOutcome processing(PilotAttempt attempt) {
        return new AttemptOutcome(AttemptOutcome.State.PROCESSING, null, attempt.getResultReason(),
                attempt.getPublicationIdentity(), false, null);
    }

    private JsonNode readArtifact(UUID executionId, String name) {
        return artifacts.getLatest(executionId, name)
                .map(artifact -> json.readTree(artifact.getContent()))
                .orElseGet(json::createObjectNode);
    }

    private static String stageOf(String resultReason, String resultState) {
        if (resultReason != null) {
            int boundary = resultReason.indexOf(": ");
            if (boundary > 0) {
                return resultReason.substring(0, boundary);
            }
        }
        return resultState.isBlank() ? null : resultState;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String capReason(String reason) {
        return reason.length() <= PersistedPilotAttemptPolicy.MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, PersistedPilotAttemptPolicy.MAX_REASON_LENGTH);
    }
}
