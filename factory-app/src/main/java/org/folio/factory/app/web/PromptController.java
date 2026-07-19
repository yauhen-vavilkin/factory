package org.folio.factory.app.web;

import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.agents.prompt.PromptOverride;
import org.folio.factory.agents.prompt.PromptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptService promptService;
    private final PromptCatalog catalog;

    public PromptController(PromptService promptService, PromptCatalog catalog) {
        this.promptService = promptService;
        this.catalog = catalog;
    }

    public record PromptSummary(String workerId, String promptName, boolean overridden, Integer activeVersion,
                                Instant updatedAt, String updatedBy) {
    }

    public record PromptVersionInfo(int version, boolean useDefault, String createdBy, Instant createdAt) {
    }

    public record PromptDetail(String workerId, String promptName, boolean overridden, Integer activeVersion,
                               String defaultContent, String effectiveContent, List<PromptVersionInfo> history) {
    }

    public record PromptVersionContent(int version, boolean useDefault, String content, String createdBy,
                                       Instant createdAt) {
    }

    public record SavePromptRequest(String content, String author) {
    }

    public record RevertPromptRequest(String author) {
    }

    @GetMapping
    public List<PromptSummary> list() {
        return catalog.all().stream()
                .map(ref -> toSummary(ref.workerId(), ref.promptName()))
                .toList();
    }

    @GetMapping("/{workerId}/{promptName}")
    public PromptDetail detail(@PathVariable("workerId") String workerId,
                               @PathVariable("promptName") String promptName) {
        return toDetail(workerId, promptName);
    }

    @GetMapping("/{workerId}/{promptName}/versions/{version}")
    public PromptVersionContent version(@PathVariable("workerId") String workerId,
                                        @PathVariable("promptName") String promptName,
                                        @PathVariable("version") int version) {
        PromptOverride override = promptService.version(workerId, promptName, version)
                .orElseThrow(() -> new NoSuchElementException(
                        "No version " + version + " for prompt " + workerId + "/" + promptName));
        return new PromptVersionContent(override.getVersion(), override.isUseDefault(), override.getContent(),
                override.getCreatedBy(), override.getCreatedAt());
    }

    @PostMapping("/{workerId}/{promptName}")
    public PromptDetail save(@PathVariable("workerId") String workerId,
                             @PathVariable("promptName") String promptName,
                             @RequestBody SavePromptRequest request) {
        promptService.saveOverride(workerId, promptName, request.content(), request.author());
        return toDetail(workerId, promptName);
    }

    @PostMapping("/{workerId}/{promptName}/revert")
    public PromptDetail revert(@PathVariable("workerId") String workerId,
                               @PathVariable("promptName") String promptName,
                               @RequestBody RevertPromptRequest request) {
        promptService.revertToDefault(workerId, promptName, request.author());
        return toDetail(workerId, promptName);
    }

    private PromptSummary toSummary(String workerId, String promptName) {
        Optional<PromptOverride> latest = promptService.latest(workerId, promptName);
        boolean overridden = latest.map(o -> !o.isUseDefault()).orElse(false);
        Integer activeVersion = overridden ? latest.get().getVersion() : null;
        return new PromptSummary(workerId, promptName, overridden, activeVersion,
                latest.map(PromptOverride::getCreatedAt).orElse(null),
                latest.map(PromptOverride::getCreatedBy).orElse(null));
    }

    private PromptDetail toDetail(String workerId, String promptName) {
        if (!catalog.exists(workerId, promptName)) {
            throw new NoSuchElementException("Unknown prompt " + workerId + "/" + promptName);
        }
        Optional<PromptOverride> latest = promptService.latest(workerId, promptName);
        boolean overridden = latest.map(o -> !o.isUseDefault()).orElse(false);
        Integer activeVersion = overridden ? latest.get().getVersion() : null;
        List<PromptVersionInfo> history = promptService.history(workerId, promptName).stream()
                .map(o -> new PromptVersionInfo(o.getVersion(), o.isUseDefault(), o.getCreatedBy(), o.getCreatedAt()))
                .toList();
        return new PromptDetail(workerId, promptName, overridden, activeVersion,
                promptService.defaultContent(workerId, promptName),
                promptService.resolve(workerId, promptName), history);
    }
}
