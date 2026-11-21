package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.Statistics;
import org.folio.factory.app.tasks.presentation.StatisticsRows;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class DeveloperStatisticsControllerTest {

    @Mock
    private StatisticsRows statisticsRows;

    @Mock
    private FlowRegistry flows;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(flows.find("dev-factory")).thenReturn(Optional.of(
                new FlowDescriptor("dev-factory", "Developer Flow", "1", null, null, null, null, null)));
        mvc = MockMvcBuilders.standaloneSetup(new DeveloperStatisticsController(statisticsRows, flows))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    @Test
    void theTabCountsTheRunsThatStartedInTheChosenPeriod() throws Exception {
        var statistics = new Statistics(new Statistics.Tasks(1, List.of(), List.of(), "Of 1 task"),
                new Statistics.Runs(1, List.of()), new Statistics.Money(List.of(), null), List.of());
        var since = ArgumentCaptor.forClass(Instant.class);
        when(statisticsRows.since(since.capture())).thenReturn(Optional.of(statistics));

        mvc.perform(get("/developer/statistics").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("developer-statistics"))
                .andExpect(model().attribute("flow", Map.of("id", "dev-factory", "name", "Developer Flow")))
                .andExpect(model().attribute("days", 30))
                .andExpect(model().attribute("periods", RunPeriod.values()))
                .andExpect(model().attribute("statistics", statistics));

        assertThat(Duration.between(since.getValue(), Instant.now()).toDays()).isEqualTo(30);
    }

    @Test
    void anUnknownPeriodIsTheDefaultOfFourteenDays() throws Exception {
        var since = ArgumentCaptor.forClass(Instant.class);
        when(statisticsRows.since(since.capture())).thenReturn(Optional.empty());

        mvc.perform(get("/developer/statistics").param("days", "5"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 14));

        assertThat(Duration.between(since.getValue(), Instant.now()).toDays()).isEqualTo(14);
    }

    @Test
    void aPeriodWithoutRunsHasNoStatistics() throws Exception {
        when(statisticsRows.since(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        mvc.perform(get("/developer/statistics"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("days", 14))
                .andExpect(model().attributeDoesNotExist("statistics"));
    }
}
