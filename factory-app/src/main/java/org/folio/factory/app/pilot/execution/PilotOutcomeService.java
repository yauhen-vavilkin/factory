package org.folio.factory.app.pilot.execution;

import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotPublicationIntentRepository;
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
 * installation's active slot: a terminal outcome with proven workload
 * inactivity releases the slot — a task-specific failure and a shared
 * provider/auth/environment failure alike stop as readable results (the shared
 * cause keeps its "shared" marker) — while any unproven cleanup or unresolved
 * publication identity holds the attempt for inspection. Engine COMPLETED
 * alone is never PR_READY — the delivered pull-request identity is the
 * evidence. Only this service releases installation.activeAttemptId.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot.execution", name = "enabled", havingValue = "true")
public class PilotOutcomeService {

    private final PilotAttemptRepository attempts;
    private final PilotInstallationRepository installations;
    private final PilotPublicationIntentRepository intents;
    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifacts;
    private final DockerWorkloads docker;
    private final FlowRegistry flows;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotOutcomeService(PilotAttemptRepository attempts,
                               PilotInstallationRepository installations,
                               PilotPublicationIntentRepository intents,
                               PipelineExecutionRepository executions,
                               ArtifactStore artifacts,
                               DockerWorkloads docker,
                               FlowRegistry flows,
                               PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.installations = installations;
        this.intents = intents;
        this.executions = executions;
        this.artifacts = artifacts;
        this.docker = docker;
        this.flows = flows;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The reason prefix of an attempt the developer marked as stopped. */
    public static final String MARKED_STOPPED = "Marked as stopped by the operator";

    /** Part of the reason when the stopped run may already have opened a pull request. */
    public static final String PULL_REQUEST_MAY_EXIST = "a pull request may already exist";

    public record AttemptOutcome(State state, String stage, String reason, String publicationIdentity,
                                 boolean workloadInactive, String uncertainty) {

        public enum State { PROCESSING, PR_READY, STOPPED, NEEDS_INSPECTION, PROCESSING_PAUSED }

        /** Only released outcomes let the dispatcher start the next task. */
        public boolean released() {
            return state == State.PR_READY || state == State.STOPPED;
        }
    }

    private record Classification(AttemptOutcome.State state, String stage, String reason,
                                  String publicationIdentity, boolean shared, String draftReasons) {

        Classification(AttemptOutcome.State state, String stage, String reason, String publicationIdentity,
                       boolean shared) {
            this(state, stage, reason, publicationIdentity, shared, null);
        }
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
                attempt.getResultReason(), pullRequestUrl, false, draftReasons(delivery)));
    }

    private AttemptOutcome resolveTerminal(PilotAttempt attempt, UUID executionId,
                                           ExecutionStatus status) {
        String uncertainty = proveInactive(executionId);
        Classification classification = classify(attempt, executionId, status);
        if (uncertainty != null) {
            return hold(attempt, uncertainty);
        }
        if (classification.shared()) {
            // Design 5.3: once inactivity is proven, a shared outage stops this
            // attempt with its persisted cause while the queue continues; the
            // same startup path rechecks the provider for the next attempts.
            return release(attempt, new Classification(AttemptOutcome.State.STOPPED,
                    classification.stage(), "shared: " + classification.reason(),
                    classification.publicationIdentity(), false));
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
                    attempt.getResultReason(), pullRequestUrl, false, draftReasons(delivery));
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
        // Installation first, then the attempt: the same order as markStopped, launch and
        // admission, so a release and a concurrent mark-as-stopped cannot deadlock.
        var installation = installations.lockById(1);
        PilotAttempt current = attempts.findById(attemptId).orElseThrow();
        current.setState(PilotAttempt.State.valueOf(classification.state().name()));
        if (classification.publicationIdentity() != null) {
            current.setPublicationIdentity(classification.publicationIdentity());
        }
        if (classification.draftReasons() != null) {
            current.setDraftReasons(classification.draftReasons());
        }
        if (current.getResultReason() == null && classification.reason() != null
                && !classification.reason().isBlank()) {
            current.setResultReason(capReason(classification.reason()));
        }
        attempts.save(current);
        installation.ifPresent(locked -> {
            if (attemptId.equals(locked.getActiveAttemptId())) {
                locked.setActiveAttemptId(null);
                installations.save(locked);
            }
        });
    }

    /**
     * The developer's explicit stop of an attempt held for inspection: it becomes
     * STOPPED with the operator's note and frees the slot only when it holds it,
     * so the queue continues and the old run can no longer publish. Any other
     * state is refused, so a stale page never stops newer work.
     *
     * @return null when the attempt was stopped, otherwise the refusal
     */
    public String markStopped(UUID attemptId) {
        return transactions.execute(status -> {
            var installation = installations.lockById(1);
            PilotAttempt attempt = attempts.findById(attemptId).orElse(null);
            if (attempt == null || attempt.getState() != PilotAttempt.State.NEEDS_INSPECTION) {
                return "Only a task that needs inspection can be marked as stopped";
            }
            String previous = attempt.getResultReason();
            // A held run that reached publication may have opened a pull request whose
            // response was lost; the reason names the branch so nobody assumes there is none.
            String publication = attempt.getExecutionId() == null ? null
                    : intents.findByExecutionId(attempt.getExecutionId())
                            .map(intent -> "; " + PULL_REQUEST_MAY_EXIST + " from " + intent.getHeadBranch()
                                    + " to " + intent.getRepository())
                            .orElse(null);
            attempt.setState(PilotAttempt.State.STOPPED);
            attempt.setResultReason(capReason(MARKED_STOPPED
                    + (publication == null ? "" : publication)
                    + (previous == null || previous.isBlank() ? "" : ": " + previous)));
            attempts.save(attempt);
            installation.filter(current -> attemptId.equals(current.getActiveAttemptId()))
                    .ifPresent(current -> {
                        current.setActiveAttemptId(null);
                        installations.save(current);
                    });
            return null;
        });
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

    /** The draft reasons DeliveryWorker wrote, as stored on the attempt; null for a pull request ready for review. */
    private String draftReasons(JsonNode delivery) {
        if (!delivery.path("pullRequestDraft").asBoolean(false)) {
            return null;
        }
        JsonNode reasons = delivery.path("draftReasons");
        return json.writeValueAsString(reasons.isArray() ? reasons : json.createArrayNode());
    }

    private JsonNode readArtifact(UUID executionId, String name) {
        return artifacts.getLatest(executionId, name)
                .map(artifact -> json.readTree(artifact.getContent()))
                .orElseGet(json::createObjectNode);
    }

    /**
     * The leading {@code stage: reason} label the outcome service composes, when
     * present; the task screens parse the same persisted reason with this rule.
     */
    public static String stageOf(String resultReason, String resultState) {
        if (resultReason != null) {
            int boundary = resultReason.indexOf(": ");
            if (boundary > 0) {
                return resultReason.substring(0, boundary);
            }
        }
        return resultState == null || resultState.isBlank() ? null : resultState;
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
