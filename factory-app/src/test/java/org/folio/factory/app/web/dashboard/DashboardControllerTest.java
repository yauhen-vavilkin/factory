package org.folio.factory.app.web.dashboard;

import org.folio.factory.app.web.ApiExceptionHandler;
import org.folio.factory.app.web.dashboard.DashboardStats.HitlStats;
import org.folio.factory.app.web.dashboard.DashboardStats.Totals;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private DashboardStatsService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private DashboardStats stats(int days) {
        return new DashboardStats(Instant.parse("2026-07-19T00:00:00Z"), days,
                new Totals(5, 3, 2, 1), List.of(), List.of(), List.of(), List.of(), List.of(),
                new HitlStats(2, 100.0, 4, 50.0), List.of(), List.of());
    }

    @Test
    void stats_defaultDays_passesThroughServiceResult() throws Exception {
        when(service.compute(14)).thenReturn(stats(14));

        mvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.totals.executions").value(5))
                .andExpect(jsonPath("$.totals.pendingReviews").value(2))
                .andExpect(jsonPath("$.hitl.pending").value(2));

        verify(service).compute(14);
    }

    @Test
    void stats_explicitDays_delegatesWithThatWindow() throws Exception {
        when(service.compute(30)).thenReturn(stats(30));

        mvc.perform(get("/api/dashboard/stats").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30));

        verify(service).compute(30);
    }

    @Test
    void stats_daysTooSmall_unprocessable() throws Exception {
        mvc.perform(get("/api/dashboard/stats").param("days", "0"))
                .andExpect(status().is(422));
        verifyNoInteractions(service);
    }

    @Test
    void stats_daysTooLarge_unprocessable() throws Exception {
        mvc.perform(get("/api/dashboard/stats").param("days", "91"))
                .andExpect(status().is(422));
        verifyNoInteractions(service);
    }
}
