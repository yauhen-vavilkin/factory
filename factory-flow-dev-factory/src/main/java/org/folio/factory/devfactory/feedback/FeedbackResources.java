package org.folio.factory.devfactory.feedback;

import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.folio.factory.devfactory.feedback.FeedbackState.Phase.INIT;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.runtime.DockerWorkloads;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Reconciles only execution-owned workloads, retaining cleanup uncertainty for the next invocation.
 */
final class FeedbackResources {
    private final AuditLog audit;
    private final DockerWorkloads docker;

    FeedbackResources(AuditLog audit, DockerWorkloads docker) {
        this.audit = audit;
        this.docker = docker;
    }

    void reconcile(AgentContext context, FeedbackState state) {
        var starts =
                audit.forExecution(context.executionId()).stream()
                        .filter(
                                e ->
                                        e.getEventType() == AuditEventType.STEP_STARTED
                                                && STEP.equals(e.getStepId()))
                        .toList();
        for (int i = starts.size() - 1; i >= 0; i--) {
            String id = JSON.readTree(starts.get(i).getDetail()).path("invocationId").asString("");
            if (id.equals(state.lastCleanedInvocation)) break;
            if (!id.equals(context.invocation().id().toString()) && !id.isBlank())
                state.cleanupSteps.add("pr-feedback-" + UUID.fromString(id));
        }
        if (JSON.readValue(context.requireInput(STATE).content(), FeedbackState.class).phase()
                == INIT)
            state.cleanupSteps.addAll(List.of("implement", "continue-implementation", "verify"));
        for (String step : new LinkedHashSet<>(state.cleanupSteps))
            docker.cleanOwned(context.executionId(), step);
        state.cleanupSteps.clear();
    }

    void cleanCurrent(AgentContext context, String operation) {
        docker.cleanOwned(context.executionId(), operation);
    }
}
