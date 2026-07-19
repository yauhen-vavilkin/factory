package org.folio.factory.app.web;

import org.folio.factory.agents.llm.PromptLoader;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.agents.prompt.PromptCatalog.PromptRef;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-rendered, read-only prompt browser. The catalog view groups bundled
 * prompt templates by worker; the detail view lays out the bundled default
 * content for the selected file. Prompts are shipped in the application jar and
 * are not editable at runtime.
 */
@Controller
public class PromptUiController {

    private static final String DEFAULT_PROMPT = "system";

    private final PromptCatalog catalog;

    public PromptUiController(PromptCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/prompts")
    public String prompts(Model model) {
        Map<String, List<String>> byWorker = new LinkedHashMap<>();
        for (PromptRef ref : catalog.all()) {
            byWorker.computeIfAbsent(ref.workerId(), w -> new ArrayList<>()).add(ref.promptName());
        }
        List<Map<String, Object>> workers = new ArrayList<>();
        byWorker.forEach((workerId, promptNames) -> {
            Map<String, Object> worker = new LinkedHashMap<>();
            worker.put("workerId", workerId);
            worker.put("prompts", promptNames);
            workers.add(worker);
        });
        model.addAttribute("workers", workers);
        return "prompts";
    }

    @GetMapping("/prompts/{workerId}")
    public String prompt(@PathVariable("workerId") String workerId,
                         @RequestParam(name = "file", required = false) String file, Model model) {
        List<String> promptNames = catalog.promptsFor(workerId);
        if (promptNames.isEmpty()) {
            return redirectToList("No prompts for worker '" + workerId + "'");
        }
        String activeFile = resolveFile(file, promptNames);
        if (activeFile == null) {
            return redirectToList("Unknown prompt '" + workerId + "/" + file + "'");
        }

        model.addAttribute("workerId", workerId);
        model.addAttribute("promptNames", promptNames);
        model.addAttribute("activeFile", activeFile);
        // Not "content": the layout fragment's `content` parameter stays in scope
        // inside the inserted page body and would shadow the model attribute.
        model.addAttribute("promptContent", PromptLoader.load(workerId, activeFile));
        return "prompt";
    }

    private String resolveFile(String file, List<String> promptNames) {
        if (file == null || file.isBlank()) {
            return promptNames.contains(DEFAULT_PROMPT) ? DEFAULT_PROMPT : promptNames.get(0);
        }
        return promptNames.contains(file) ? file : null;
    }

    private String redirectToList(String message) {
        return "redirect:/prompts?error=" + UiFormat.encode(message);
    }
}
