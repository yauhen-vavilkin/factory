package org.folio.factory.app.web;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class DeveloperPreflightController {
    private final DeveloperPreflight preflight;

    public DeveloperPreflightController(DeveloperPreflight preflight) {
        this.preflight = preflight;
    }

    @GetMapping("/api/developer/preflight")
    @ResponseBody
    public DeveloperPreflight.Report api(@RequestParam(name = "issueKey", required = false) String issueKey) {
        return preflight.check(issueKey);
    }

    @GetMapping("/developer/configuration")
    public String configuration(@RequestParam(name = "issueKey", required = false) String issueKey, Model model) {
        model.addAttribute("report", preflight.check(issueKey));
        return "developer-configuration";
    }
}
