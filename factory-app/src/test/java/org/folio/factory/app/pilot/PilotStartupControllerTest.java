package org.folio.factory.app.pilot;

import org.folio.factory.app.pilot.startup.PilotReadiness;
import org.folio.factory.app.pilot.startup.PilotRuntimeSmoke;
import org.folio.factory.app.pilot.startup.PilotStartupService;
import org.folio.factory.app.web.PilotStartupController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PilotStartupControllerTest {
    @Mock
    private PilotStartupService startup;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PilotStartupController(startup)).build();
    }

    @Test
    void startDelegatesToTheExplicitServiceOperationAndReturnsReadiness() throws Exception {
        when(startup.start()).thenReturn(ready());

        mvc.perform(post("/api/pilot/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionReady").value(true))
                .andExpect(jsonPath("$.discoveryAvailable").value(false))
                .andExpect(jsonPath("$.blockers").isEmpty())
                .andExpect(jsonPath("$.evidenceCurrent").value(true))
                .andExpect(jsonPath("$.smokeDisclosure").value("disclosure text"))
                .andExpect(jsonPath("$.smoke.success").value(true));

        verify(startup).start();
        verifyNoMoreInteractions(startup);
    }

    @Test
    void statusDelegatesToTheReadOnlyReportOnly() throws Exception {
        when(startup.status()).thenReturn(blocked());

        mvc.perform(get("/api/pilot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionReady").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("Runtime check has not completed"))
                .andExpect(jsonPath("$.smoke").doesNotExist());

        verify(startup).status();
        verifyNoMoreInteractions(startup);
    }

    private static PilotReadiness ready() {
        var smoke = new PilotRuntimeSmoke.SmokeEvidence("f".repeat(64), "pinned:image", "claude-code",
                "exact-model", "http://gateway.fixture.invalid:4001", "i".repeat(64),
                true, true, true, true, null, Instant.parse("2026-10-24T08:00:00Z"));
        return new PilotReadiness(false, true, List.of(), "f".repeat(64), true, smoke,
                "disclosure text", Instant.parse("2026-10-24T08:00:00Z"));
    }

    private static PilotReadiness blocked() {
        return new PilotReadiness(false, false, List.of("Runtime check has not completed"),
                "f".repeat(64), false, null, "disclosure text", null);
    }
}
