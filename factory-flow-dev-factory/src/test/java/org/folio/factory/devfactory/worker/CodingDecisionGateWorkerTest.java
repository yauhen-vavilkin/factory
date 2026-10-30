package org.folio.factory.devfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.pilot.RecordingPilotAttempts;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingDecisionGateWorkerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void opensOnlyForNeedsDecision() {
        ConditionalDecisionGate gate = mock(ConditionalDecisionGate.class);
        var context = context(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.REPOSITORY_MISMATCH, "Correct repository?", List.of(), "Evidence"),
                Map.of()));
        when(gate.open(context, CodingDecisionGateWorker.GATE)).thenReturn(true);

        assertThat(new CodingDecisionGateWorker(gate).execute(context).metrics())
                .containsEntry("decisionRequired", true).containsEntry("reviewOpened", true);
        verify(gate).open(context, CodingDecisionGateWorker.GATE);
    }

    @Test void completedOutcomeDoesNotOpenReview() {
        ConditionalDecisionGate gate = mock(ConditionalDecisionGate.class);
        var context = context(CodingOutcome.completed("Done", Map.of()));

        assertThat(new CodingDecisionGateWorker(gate).execute(context).metrics())
                .containsEntry("decisionRequired", false).containsEntry("reviewOpened", false);
        verify(gate, never()).open(context, CodingDecisionGateWorker.GATE);
    }

    @Test void pilotNeedsDecisionRecordsAStopInsteadOfOpeningReview() {
        ConditionalDecisionGate gate = mock(ConditionalDecisionGate.class);
        var pilots = RecordingPilotAttempts.pilot();
        var context = context(CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Which length limit applies?",
                List.of(), "No limit is specified"), Map.of()));

        assertThat(new CodingDecisionGateWorker(gate, pilots).execute(context).metrics())
                .containsEntry("decisionRequired", true).containsEntry("reviewOpened", false);

        verify(gate, never()).open(context, CodingDecisionGateWorker.GATE);
        assertThat(pilots.stops).singleElement().satisfies(stop -> {
            assertThat(stop).startsWith("clarify-implementation: ");
            assertThat(stop).contains("Which length limit applies?");
        });
    }

    private AgentContext context(CodingOutcome outcome) {
        return new AgentContext(UUID.randomUUID(), "clarify-implementation", Map.of(
                DevelopWorker.OUTCOME, new ArtifactContent(DevelopWorker.OUTCOME, 1,
                        "application/json", json.writeValueAsString(outcome))), null, Map.of(), List.of());
    }
}
