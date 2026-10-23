package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.limits.DedupKeyDeriver;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TriggerPreflightTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final FlowRegistry flows = mock(FlowRegistry.class);
    private final StateManager state = mock(StateManager.class);
    private final TriggerPreflight preflight = mock(TriggerPreflight.class);
    private final LimitsProperties limits = new LimitsProperties(0, 0, null, null, null);
    private final PipelineRouter router = new PipelineRouter(flows, state, json, limits,
            new DedupKeyDeriver(limits, json), mock(AuditLog.class), List.of(preflight));
    private final JsonNode payload = json.readTree("{\"issueKey\":\"TASK-1\"}");
    private final FlowDescriptor first = flow("first");
    private final FlowDescriptor second = flow("second");

    @BeforeEach
    void setup() {
        when(flows.require("first")).thenReturn(first);
        when(flows.all()).thenReturn(List.of(first, second));
    }

    @Test
    void invalidManualTriggerCreatesNoExecution() {
        doThrow(new IllegalArgumentException("configuration failed")).when(preflight).requireReady("first", payload);

        assertThatThrownBy(() -> router.routeManual("first", payload)).hasMessage("configuration failed");
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void successfulAdmissionRunsBeforeCreation() {
        var execution = new PipelineExecution("first", "1", "{}");
        when(state.createExecution(eq("first"), eq("1"), anyString(), isNull())).thenReturn(execution);

        assertThat(router.routeManual("first", payload)).isEqualTo(execution.getId());
        var order = inOrder(preflight, state);
        order.verify(preflight).requireReady("first", payload);
        order.verify(state).createExecution(eq("first"), eq("1"), anyString(), isNull());
    }

    @Test
    void duplicateManualTriggerDoesNotRepeatAdmissionOrCreate() {
        var existing = new PipelineExecution("first", "1", "{}");
        when(state.findRecentDuplicate("first", "unique", limits.dedup().window())).thenReturn(Optional.of(existing));

        assertThat(router.routeManual("first", payload, "unique")).isEqualTo(existing.getId());
        verifyNoInteractions(preflight);
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void webhookFanoutChecksAllCandidatesBeforeCreatingAnyExecution() {
        doThrow(new IllegalArgumentException("second blocked")).when(preflight).requireReady("second", payload);

        assertThatThrownBy(() -> router.route(TriggerEvent.of("test", "webhook", payload))).hasMessage("second blocked");
        verify(preflight).requireReady("first", payload);
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void webhookDuplicatesDoNotRepeatAdmission() {
        var existing = new PipelineExecution("first", "1", "{}");
        when(flows.all()).thenReturn(List.of(first));
        when(state.findRecentDuplicate(eq("first"), anyString(), any())).thenReturn(Optional.of(existing));

        assertThat(router.route(TriggerEvent.of("test", "webhook", payload))).containsExactly(existing.getId());
        verifyNoInteractions(preflight);
    }

    @Test
    void prepareManualValidatesAndCarriesIdentityWithoutCreating() {
        var prepared = router.prepareManual("first", payload, "unique");

        assertThat(prepared.flowId()).isEqualTo("first");
        assertThat(prepared.payload()).isSameAs(payload);
        assertThat(prepared.dedupKey()).isEqualTo("unique");
        verify(preflight).requireReady("first", payload);
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void failedPreparationCreatesNothing() {
        doThrow(new IllegalArgumentException("not ready")).when(preflight).requireReady("first", payload);

        assertThatThrownBy(() -> router.prepareManual("first", payload, "unique"))
                .hasMessage("not ready");
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void oversizePreparedPayloadIsRejectedBeforePreflight() {
        JsonNode giant = json.readTree("{\"issueKey\":\"" + "x".repeat(300_000) + "\"}");

        assertThatThrownBy(() -> router.prepareManual("first", giant, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bytes");
        verifyNoInteractions(preflight);
    }

    @Test
    void routePreparedChecksIdentityDedupAndBudgetWithoutRepeatingPreflight() {
        var execution = new PipelineExecution("first", "1", "{}");
        when(state.createExecution(eq("first"), eq("1"), anyString(), eq("unique"))).thenReturn(execution);
        var prepared = router.prepareManual("first", payload, "unique");

        assertThat(router.routePrepared(prepared)).isEqualTo(execution.getId());

        var order = inOrder(state);
        order.verify(state).findRecentDuplicate("first", "unique", limits.dedup().window());
        order.verify(state).createExecution(eq("first"), eq("1"), anyString(), eq("unique"));
        verify(preflight, times(1)).requireReady(anyString(), any());
    }

    @Test
    void routePreparedRevalidatesFlowIdentity() {
        when(flows.require("missing")).thenThrow(new FlowValidationException("No registered flow with id 'missing'"));

        assertThatThrownBy(() -> router.routePrepared(new PreparedManualTrigger("missing", payload, "unique")))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("missing");
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    @Test
    void routePreparedDedupesOntoExistingExecution() {
        var existing = new PipelineExecution("first", "1", "{}");
        when(state.findRecentDuplicate("first", "unique", limits.dedup().window()))
                .thenReturn(Optional.of(existing));
        var prepared = router.prepareManual("first", payload, "unique");

        assertThat(router.routePrepared(prepared)).isEqualTo(existing.getId());
        verify(state, never()).createExecution(anyString(), anyString(), any(), any());
    }

    private static FlowDescriptor flow(String id) {
        return new FlowDescriptor(id, id, "1", List.of(new TriggerContract("test", Map.of())),
                null, null, List.of(), null);
    }
}
