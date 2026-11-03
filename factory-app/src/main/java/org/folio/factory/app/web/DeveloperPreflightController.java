package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
public class DeveloperPreflightController {
    private final DeveloperPreflight preflight;
    private final TaskRows rows;
    private final TaskInstallationRepository installations;

    @GetMapping("/api/developer/preflight")
    @ResponseBody
    public DeveloperPreflight.Report api(@RequestParam(name = "issueKey", required = false) String issueKey) {
        return preflight.check(issueKey);
    }

    @GetMapping("/developer/configuration")
    public String configuration(@RequestParam(name = "issueKey", required = false) String issueKey, Model model) {
        model.addAttribute("report", preflight.check(issueKey));
        // Where My tasks come from; the My tasks page keeps only the scan and its problems.
        model.addAttribute("source", installations.findById(1).map(rows::sourceCard).orElse(null));
        model.addAttribute("repositoryMap", rows.repositoryMap());
        return "developer-configuration";
    }
}
