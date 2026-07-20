package org.folio.factory.app.web.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final int MAX_DAYS = 90;

    private final DashboardStatsService stats;

    public DashboardController(DashboardStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    public DashboardStats stats(@RequestParam(name = "days", defaultValue = "14") int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        return stats.compute(days);
    }
}
