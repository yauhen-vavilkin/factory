package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.core.registry.FlowRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.folio.factory.devfactory.DevFactoryConfiguration.FLOW_ID;

@Controller
@RequiredArgsConstructor
public class DeveloperPreflightController {
    private final DeveloperPreflight preflight;
    private final DeveloperConfigurationPage page;
    private final FlowRegistry flows;

    @GetMapping("/api/developer/preflight")
    @ResponseBody
    public DeveloperPreflight.Report api(@RequestParam(name = "issueKey", required = false) String issueKey) {
        return preflight.check(issueKey);
    }

    /** The Configuration tab of the Developer Flow page. */
    @GetMapping("/developer/configuration")
    public String configuration(@RequestParam(name = "issueKey", required = false) String issueKey, Model model) {
        var descriptor = flows.find(FLOW_ID)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + FLOW_ID + "'"));
        model.addAttribute("flow", Map.of("id", descriptor.id(), "name", descriptor.name()));
        model.addAttribute("configuration", page.view(issueKey));
        return "developer-configuration";
    }
}
