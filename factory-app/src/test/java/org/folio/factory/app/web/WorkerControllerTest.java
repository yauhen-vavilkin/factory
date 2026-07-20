package org.folio.factory.app.web;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerControllerTest {

    @Mock
    private AgentWorkerRegistry registry;

    @Mock
    private PromptCatalog catalog;

    @Mock
    private FlowRegistry flowRegistry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WorkerController(registry, catalog, flowRegistry)).build();
    }

    static final class LlmTestWorker extends AbstractLlmAgentWorker {
        LlmTestWorker() {
            super(null);
        }

        @Override
        public String id() {
            return "triage";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return null;
        }
    }

    static final class PlainTestWorker implements AgentWorker {
        @Override
        public String id() {
            return "finalizer";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            return null;
        }
    }

    @Test
    void list_reportsLlmFlagPromptsAndUsedBy() throws Exception {
        AgentWorker llm = new LlmTestWorker();
        AgentWorker plain = new PlainTestWorker();
        when(registry.all()).thenReturn(Map.of("triage", llm, "finalizer", plain));

        StepDescriptor agentStep = new StepDescriptor("triage-step", StepType.AGENT, "triage",
                null, null, List.of(), List.of(), Map.of());
        FlowDescriptor flow = new FlowDescriptor("test-factory", "Test Factory", "1.0.0",
                List.of(), null, null, List.of(agentStep), RetryPolicy.DEFAULT);
        when(flowRegistry.all()).thenReturn(List.of(flow));
        when(catalog.promptsFor("triage")).thenReturn(List.of("system", "user"));
        when(catalog.promptsFor("finalizer")).thenReturn(List.of());

        mvc.perform(get("/api/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("finalizer"))
                .andExpect(jsonPath("$[0].llm").value(false))
                .andExpect(jsonPath("$[0].prompts").isEmpty())
                .andExpect(jsonPath("$[0].usedBy").isEmpty())
                .andExpect(jsonPath("$[1].id").value("triage"))
                .andExpect(jsonPath("$[1].className").value(LlmTestWorker.class.getName()))
                .andExpect(jsonPath("$[1].llm").value(true))
                .andExpect(jsonPath("$[1].prompts[0]").value("system"))
                .andExpect(jsonPath("$[1].prompts[1]").value("user"))
                .andExpect(jsonPath("$[1].usedBy[0].flowId").value("test-factory"))
                .andExpect(jsonPath("$[1].usedBy[0].stepId").value("triage-step"));
    }
}
