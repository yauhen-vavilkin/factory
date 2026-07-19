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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PromptControllerTest {

    @Mock
    private PromptCatalog catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PromptController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void list_returnsWorkerIdAndPromptNameOnly() throws Exception {
        when(catalog.all()).thenReturn(List.of(
                new PromptRef("triage-agent", "system"), new PromptRef("test-spec-agent", "user")));

        mvc.perform(get("/api/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workerId").value("triage-agent"))
                .andExpect(jsonPath("$[0].promptName").value("system"))
                .andExpect(jsonPath("$[0].overridden").doesNotExist())
                .andExpect(jsonPath("$[1].workerId").value("test-spec-agent"))
                .andExpect(jsonPath("$[1].promptName").value("user"));
    }

    @Test
    void detail_knownPair_returnsBundledContent() throws Exception {
        when(catalog.exists("triage-agent", "system")).thenReturn(true);

        mvc.perform(get("/api/prompts/{w}/{p}", "triage-agent", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("triage-agent"))
                .andExpect(jsonPath("$.promptName").value("system"))
                .andExpect(jsonPath("$.content").isNotEmpty())
                .andExpect(jsonPath("$.effectiveContent").doesNotExist());
    }

    @Test
    void detail_unknownPair_notFound() throws Exception {
        when(catalog.exists("nope", "system")).thenReturn(false);

        mvc.perform(get("/api/prompts/{w}/{p}", "nope", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("Unknown prompt")));
    }
}
