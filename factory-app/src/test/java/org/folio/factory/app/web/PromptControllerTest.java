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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PromptControllerTest {

    @Mock
    private PromptService promptService;

    @Mock
    private PromptCatalog catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PromptController(promptService, catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private PromptOverride override(String worker, String prompt, int version, boolean useDefault, String content) {
        return new PromptOverride(worker, prompt, version, useDefault, content, "qa-lead");
    }

    private void stubDetail(String worker, String prompt, PromptOverride latest, List<PromptOverride> history) {
        when(catalog.exists(worker, prompt)).thenReturn(true);
        when(promptService.latest(worker, prompt)).thenReturn(Optional.ofNullable(latest));
        when(promptService.history(worker, prompt)).thenReturn(history);
        when(promptService.defaultContent(worker, prompt)).thenReturn("DEFAULT");
        when(promptService.resolve(worker, prompt))
                .thenReturn(latest != null && !latest.isUseDefault() ? latest.getContent() : "DEFAULT");
    }

    @Test
    void list_mapsOverriddenAndDefaultSummaries() throws Exception {
        when(catalog.all()).thenReturn(List.of(
                new PromptRef("triage", "system"), new PromptRef("test-spec", "user")));
        when(promptService.latest("triage", "system"))
                .thenReturn(Optional.of(override("triage", "system", 3, false, "CUSTOM")));
        when(promptService.latest("test-spec", "user")).thenReturn(Optional.empty());

        mvc.perform(get("/api/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workerId").value("triage"))
                .andExpect(jsonPath("$[0].promptName").value("system"))
                .andExpect(jsonPath("$[0].overridden").value(true))
                .andExpect(jsonPath("$[0].activeVersion").value(3))
                .andExpect(jsonPath("$[0].updatedBy").value("qa-lead"))
                .andExpect(jsonPath("$[1].workerId").value("test-spec"))
                .andExpect(jsonPath("$[1].overridden").value(false))
                .andExpect(jsonPath("$[1].activeVersion").value(nullValue()))
                .andExpect(jsonPath("$[1].updatedBy").value(nullValue()));
    }

    @Test
    void detail_mapsEffectiveContentAndHistory() throws Exception {
        PromptOverride latest = override("triage", "system", 2, false, "CUSTOM");
        stubDetail("triage", "system", latest, List.of(latest, override("triage", "system", 1, false, "OLD")));

        mvc.perform(get("/api/prompts/{w}/{p}", "triage", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("triage"))
                .andExpect(jsonPath("$.promptName").value("system"))
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.activeVersion").value(2))
                .andExpect(jsonPath("$.defaultContent").value("DEFAULT"))
                .andExpect(jsonPath("$.effectiveContent").value("CUSTOM"))
                .andExpect(jsonPath("$.history[0].version").value(2))
                .andExpect(jsonPath("$.history[0].useDefault").value(false))
                .andExpect(jsonPath("$.history[1].version").value(1));
    }

    @Test
    void detail_unknownPair_notFound() throws Exception {
        when(catalog.exists("nope", "system")).thenReturn(false);

        mvc.perform(get("/api/prompts/{w}/{p}", "nope", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("Unknown prompt")));
    }

    @Test
    void version_present_returnsContent() throws Exception {
        when(promptService.version("triage", "system", 2))
                .thenReturn(Optional.of(override("triage", "system", 2, false, "CUSTOM")));

        mvc.perform(get("/api/prompts/{w}/{p}/versions/{v}", "triage", "system", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.useDefault").value(false))
                .andExpect(jsonPath("$.content").value("CUSTOM"))
                .andExpect(jsonPath("$.createdBy").value("qa-lead"));
    }

    @Test
    void version_absent_notFound() throws Exception {
        when(promptService.version("triage", "system", 9)).thenReturn(Optional.empty());

        mvc.perform(get("/api/prompts/{w}/{p}/versions/{v}", "triage", "system", 9))
                .andExpect(status().isNotFound());
    }

    @Test
    void save_delegatesAndReturnsDetail() throws Exception {
        stubDetail("triage", "system", override("triage", "system", 1, false, "NEW"),
                List.of(override("triage", "system", 1, false, "NEW")));

        mvc.perform(post("/api/prompts/{w}/{p}", "triage", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"NEW\",\"author\":\"qa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveContent").value("NEW"));

        verify(promptService).saveOverride("triage", "system", "NEW", "qa");
    }

    @Test
    void save_blankContent_unprocessable() throws Exception {
        when(promptService.saveOverride("triage", "system", "", "qa"))
                .thenThrow(new IllegalArgumentException("Prompt content must not be blank"));

        mvc.perform(post("/api/prompts/{w}/{p}", "triage", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\",\"author\":\"qa\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("must not be blank")));
    }

    @Test
    void save_unknownPair_notFound() throws Exception {
        when(promptService.saveOverride("nope", "system", "NEW", "qa"))
                .thenThrow(new NoSuchElementException("Unknown prompt nope/system"));

        mvc.perform(post("/api/prompts/{w}/{p}", "nope", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"NEW\",\"author\":\"qa\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void revert_delegatesAndReturnsDetail() throws Exception {
        stubDetail("triage", "system", override("triage", "system", 2, true, null),
                List.of(override("triage", "system", 2, true, null)));

        mvc.perform(post("/api/prompts/{w}/{p}/revert", "triage", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"author\":\"qa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false))
                .andExpect(jsonPath("$.effectiveContent").value("DEFAULT"));

        verify(promptService).revertToDefault("triage", "system", "qa");
    }

    @Test
    void revert_alreadyDefault_conflict() throws Exception {
        when(promptService.revertToDefault("triage", "system", "qa"))
                .thenThrow(new IllegalStateException("Default prompt is already in effect for triage/system"));

        mvc.perform(post("/api/prompts/{w}/{p}/revert", "triage", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"author\":\"qa\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already in effect")));
    }
}
