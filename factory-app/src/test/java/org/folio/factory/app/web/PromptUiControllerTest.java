package org.folio.factory.app.web;

import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.agents.prompt.PromptCatalog.PromptRef;
import org.folio.factory.agents.prompt.PromptOverride;
import org.folio.factory.agents.prompt.PromptService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class PromptUiControllerTest {

    @Mock
    private PromptCatalog catalog;

    @Mock
    private PromptService promptService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new PromptUiController(catalog, promptService))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    private static PromptOverride override(String worker, String prompt, int version, boolean useDefault,
                                           String content) {
        return new PromptOverride(worker, prompt, version, useDefault, content, "qa-lead");
    }

    // ----- list -----

    @Test
    @SuppressWarnings("unchecked")
    void list_groupsPromptsByWorker() throws Exception {
        when(catalog.all()).thenReturn(List.of(
                new PromptRef("triage-agent", "system"),
                new PromptRef("triage-agent", "user"),
                new PromptRef("test-spec-agent", "system")));
        when(promptService.latest("triage-agent", "system"))
                .thenReturn(Optional.of(override("triage-agent", "system", 3, false, "CUSTOM")));
        when(promptService.latest("triage-agent", "user")).thenReturn(Optional.empty());
        when(promptService.latest("test-spec-agent", "system")).thenReturn(Optional.empty());

        var result = mvc.perform(get("/prompts"))
                .andExpect(status().isOk())
                .andExpect(view().name("prompts"))
                .andReturn();

        List<Map<String, Object>> workers =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("workers");
        assertThat(workers).hasSize(2);
        assertThat(workers.get(0)).containsEntry("workerId", "triage-agent");
        List<Map<String, Object>> prompts = (List<Map<String, Object>>) workers.get(0).get("prompts");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(0))
                .containsEntry("name", "system")
                .containsEntry("overridden", true)
                .containsEntry("activeVersion", 3)
                .containsEntry("updatedBy", "qa-lead");
        assertThat(prompts.get(1)).containsEntry("name", "user").containsEntry("overridden", false);
        assertThat(workers.get(1)).containsEntry("workerId", "test-spec-agent");
    }

    // ----- detail -----

    @Test
    void detail_overridden_mapsEffectiveAndReference() throws Exception {
        PromptOverride latest = override("triage-agent", "system", 2, false, "CUSTOM");
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("system", "user"));
        when(promptService.latest("triage-agent", "system")).thenReturn(Optional.of(latest));
        when(promptService.defaultContent("triage-agent", "system")).thenReturn("DEFAULT");
        when(promptService.resolve("triage-agent", "system")).thenReturn("CUSTOM");
        when(promptService.history("triage-agent", "system")).thenReturn(List.of(latest,
                override("triage-agent", "system", 1, false, "OLD")));

        mvc.perform(get("/prompts/{w}", "triage-agent").param("file", "system"))
                .andExpect(status().isOk())
                .andExpect(view().name("prompt"))
                .andExpect(model().attribute("activeFile", "system"))
                .andExpect(model().attribute("overridden", true))
                .andExpect(model().attribute("activeVersion", 2))
                .andExpect(model().attribute("referenceLabel", "Bundled default"))
                .andExpect(model().attribute("referenceContent", "DEFAULT"))
                .andExpect(model().attribute("effectiveContent", "CUSTOM"));
    }

    @Test
    void detail_defaultFileResolution_prefersSystem() throws Exception {
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("user", "system"));
        when(promptService.latest("triage-agent", "system")).thenReturn(Optional.empty());
        when(promptService.defaultContent("triage-agent", "system")).thenReturn("DEFAULT");
        when(promptService.resolve("triage-agent", "system")).thenReturn("DEFAULT");
        when(promptService.history("triage-agent", "system")).thenReturn(List.of());

        mvc.perform(get("/prompts/{w}", "triage-agent"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeFile", "system"))
                .andExpect(model().attribute("overridden", false))
                .andExpect(model().attribute("activeVersion", (Object) null));
    }

    @Test
    void detail_compareVersion_showsThatVersionInReference() throws Exception {
        when(catalog.promptsFor("triage-agent")).thenReturn(List.of("system", "user"));
        when(promptService.latest("triage-agent", "system"))
                .thenReturn(Optional.of(override("triage-agent", "system", 2, false, "CUSTOM")));
        when(promptService.resolve("triage-agent", "system")).thenReturn("CUSTOM");
        when(promptService.history("triage-agent", "system")).thenReturn(List.of());
        when(promptService.version("triage-agent", "system", 1))
                .thenReturn(Optional.of(override("triage-agent", "system", 1, false, "OLD")));

        mvc.perform(get("/prompts/{w}", "triage-agent").param("file", "system").param("compare", "1"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("referenceLabel", "Version 1"))
                .andExpect(model().attribute("referenceContent", "OLD"));
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

    // ----- save / revert -----

    @Test
    void save_delegatesAndFlashesVersion() throws Exception {
        when(promptService.saveOverride("triage-agent", "system", "NEW", "qa"))
                .thenReturn(override("triage-agent", "system", 4, false, "NEW"));

        mvc.perform(post("/prompts/{w}/save", "triage-agent")
                        .param("file", "system").param("content", "NEW").param("author", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/prompts/triage-agent?file=system"))
                .andExpect(flash().attribute("message", "Saved prompt version 4"));

        verify(promptService).saveOverride("triage-agent", "system", "NEW", "qa");
    }

    @Test
    void save_serviceRejects_redirectsToEditorWithError() throws Exception {
        when(promptService.saveOverride("triage-agent", "system", "", "qa"))
                .thenThrow(new IllegalArgumentException("Prompt content must not be blank"));

        mvc.perform(post("/prompts/{w}/save", "triage-agent")
                        .param("file", "system").param("content", "").param("author", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/prompts/triage-agent?file=system&error=")
                        .contains("blank"));
    }

    @Test
    void revert_delegatesAndFlashes() throws Exception {
        when(promptService.revertToDefault("triage-agent", "system", "qa"))
                .thenReturn(override("triage-agent", "system", 5, true, null));

        mvc.perform(post("/prompts/{w}/revert", "triage-agent")
                        .param("file", "system").param("author", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/prompts/triage-agent?file=system"))
                .andExpect(flash().attribute("message", "Reverted to default"));

        verify(promptService).revertToDefault("triage-agent", "system", "qa");
    }

    @Test
    void revert_alreadyDefault_redirectsToEditorWithError() throws Exception {
        when(promptService.revertToDefault("triage-agent", "system", "qa"))
                .thenThrow(new IllegalStateException("Default prompt is already in effect for triage-agent/system"));

        mvc.perform(post("/prompts/{w}/revert", "triage-agent")
                        .param("file", "system").param("author", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/prompts/triage-agent?file=system&error=")
                        .contains("already"));
    }
}
