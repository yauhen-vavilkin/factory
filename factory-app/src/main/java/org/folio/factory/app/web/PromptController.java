package org.folio.factory.app.web;

import org.folio.factory.agents.llm.PromptLoader;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptCatalog catalog;

    public PromptController(PromptCatalog catalog) {
        this.catalog = catalog;
    }

    public record PromptSummary(String workerId, String promptName) {
    }

    public record PromptDetail(String workerId, String promptName, String content) {
    }

    @GetMapping
    public List<PromptSummary> list() {
        return catalog.all().stream()
                .map(ref -> new PromptSummary(ref.workerId(), ref.promptName()))
                .toList();
    }

    @GetMapping("/{workerId}/{promptName}")
    public PromptDetail detail(@PathVariable("workerId") String workerId,
                               @PathVariable("promptName") String promptName) {
        if (!catalog.exists(workerId, promptName)) {
            throw new NoSuchElementException("Unknown prompt " + workerId + "/" + promptName);
        }
        return new PromptDetail(workerId, promptName, PromptLoader.load(workerId, promptName));
    }
}
