package org.folio.factory.app.web;

import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.agents.prompt.PromptCatalog.PromptRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class PromptUiControllerTest {

    @Mock
    private PromptCatalog catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new PromptUiController(catalog))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    // ----- list -----

    @Test
    @SuppressWarnings("unchecked")
    void list_groupsPromptsByWorker() throws Exception {
        when(catalog.all()).thenReturn(List.of(
                new PromptRef("triage-agent", "system"),
                new PromptRef("triage-agent", "user"),
                new PromptRef("test-spec-agent", "system")));

        var result = mvc.perform(get("/prompts"))
                .andExpect(status().isOk())
                .andExpect(view().name("prompts"))
                .andReturn();

        List<Map<String, Object>> workers =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("workers");
        assertThat(workers).hasSize(2);
        assertThat(workers.get(0)).containsEntry("workerId", "triage-agent");
        assertThat((List<String>) workers.get(0).get("prompts")).containsExactly("system", "user");
        assertThat(workers.get(1)).containsEntry("workerId", "test-spec-agent");
        assertThat((List<String>) workers.get(1).get("prompts")).containsExactly("system");
    }

    // ----- detail -----

    @Test
    void detail_showsBundledContentForSelectedFile() throws Exception {
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("system", "user"));

        mvc.perform(get("/prompts/{w}", "triage-agent").param("file", "user"))
                .andExpect(status().isOk())
                .andExpect(view().name("prompt"))
                .andExpect(model().attribute("workerId", "triage-agent"))
                .andExpect(model().attribute("activeFile", "user"))
                .andExpect(model().attributeExists("promptContent"));
    }

    @Test
    void detail_defaultFileResolution_prefersSystem() throws Exception {
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("user", "system"));

        mvc.perform(get("/prompts/{w}", "triage-agent"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeFile", "system"));
    }

    @Test
    void detail_unknownWorker_redirectsToListWithError() throws Exception {
        when(catalog.promptsFor("ghost")).thenReturn(List.of());

        mvc.perform(get("/prompts/{w}", "ghost"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/prompts?error="));
    }

    @Test
    void detail_unknownFile_redirectsToListWithError() throws Exception {
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("system", "user"));

        mvc.perform(get("/prompts/{w}", "triage-agent").param("file", "bogus"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/prompts?error=")
                        .contains("bogus"));
    }
}
