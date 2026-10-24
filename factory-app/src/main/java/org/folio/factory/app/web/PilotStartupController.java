package org.folio.factory.app.web;

import org.folio.factory.app.pilot.startup.PilotReadiness;
import org.folio.factory.app.pilot.startup.PilotStartupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The explicit pilot startup operations. POST /api/pilot/start is the only
 * route that may run the disclosed bounded smoke; GET /api/pilot/status reports
 * progress and result without any model call. The Developer Flow preflight
 * controller keeps its read-only GET report and never triggers this action.
 */
@RestController
public class PilotStartupController {

    private final PilotStartupService startup;

    public PilotStartupController(PilotStartupService startup) {
        this.startup = startup;
    }

    @PostMapping("/api/pilot/start")
    public PilotReadiness start() {
        return startup.start();
    }

    @GetMapping("/api/pilot/status")
    public PilotReadiness status() {
        return startup.status();
    }
}
