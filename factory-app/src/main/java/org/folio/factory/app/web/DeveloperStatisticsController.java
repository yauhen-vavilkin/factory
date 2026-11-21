package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.presentation.StatisticsRows;
import org.folio.factory.core.registry.FlowRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.folio.factory.devfactory.DevFactoryConfiguration.FLOW_ID;

/** The Statistics tab of the Developer Flow page, for the periods of the Dashboard. */
@Controller
@RequiredArgsConstructor
public class DeveloperStatisticsController {

    private final StatisticsRows statistics;
    private final FlowRegistry flows;

    /** {@code statistics} is left out for a period in which no run started. */
    @GetMapping("/developer/statistics")
    public String statistics(@RequestParam(name = "days", defaultValue = "14") String days, Model model) {
        var descriptor = flows.find(FLOW_ID)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + FLOW_ID + "'"));
        var period = RunPeriod.of(days).orElse(RunPeriod.DEFAULT);
        model.addAttribute("flow", Map.of("id", descriptor.id(), "name", descriptor.name()));
        model.addAttribute("periods", RunPeriod.values());
        model.addAttribute("days", period.days());
        statistics.since(period.since(Instant.now()).toInstant())
                .ifPresent(found -> model.addAttribute("statistics", found));
        return "developer-statistics";
    }
}
