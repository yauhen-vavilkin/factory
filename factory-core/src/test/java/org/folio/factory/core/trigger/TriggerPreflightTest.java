package org.folio.factory.core.trigger;

import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.limits.DedupKeyDeriver;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.registry.FlowRegistry;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private static FlowDescriptor flow(String id) {
        return new FlowDescriptor(id, id, "1", List.of(new TriggerContract("test", Map.of())),
                null, null, List.of(), null);
    }
}
