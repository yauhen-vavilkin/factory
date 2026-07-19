package org.folio.factory.app.web;

import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.agents.prompt.PromptCatalog.PromptRef;
import org.folio.factory.agents.prompt.PromptOverride;
import org.folio.factory.agents.prompt.PromptService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Server-rendered prompt browser and editor. The catalog view groups bundled
 * prompt templates by worker; the editor lays the bundled default beside the
 * effective content and delegates save/revert to the same {@link PromptService}
 * the REST {@link PromptController} uses. Overrides are insert-only versions, so
 * every save/revert appends a new attributed history entry.
 */
@Controller
public class PromptUiController {

    private static final String DEFAULT_PROMPT = "system";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PromptCatalog catalog;
    private final PromptService promptService;

    public PromptUiController(PromptCatalog catalog, PromptService promptService) {
        this.catalog = catalog;
        this.promptService = promptService;
    }

    @GetMapping("/prompts")
    public String prompts(Model model) {
        Map<String, List<Map<String, Object>>> byWorker = new LinkedHashMap<>();
        for (PromptRef ref : catalog.all()) {
            byWorker.computeIfAbsent(ref.workerId(), w -> new ArrayList<>())
                    .add(promptSummaryRow(ref.workerId(), ref.promptName()));
        }
        List<Map<String, Object>> workers = new ArrayList<>();
        byWorker.forEach((workerId, promptRows) -> {
            Map<String, Object> worker = new LinkedHashMap<>();
            worker.put("workerId", workerId);
            worker.put("prompts", promptRows);
            workers.add(worker);
        });
        model.addAttribute("workers", workers);
        return "prompts";
    }

    @GetMapping("/prompts/{workerId}")
    public String prompt(@PathVariable("workerId") String workerId,
                         @RequestParam(name = "file", required = false) String file,
                         @RequestParam(name = "compare", required = false) String compare,
                         @RequestParam(name = "error", required = false) String error, Model model) {
        List<String> promptNames = catalog.promptsFor(workerId);
        if (promptNames.isEmpty()) {
            return redirectToList("No prompts for worker '" + workerId + "'");
        }
        String activeFile = resolveFile(file, promptNames);
        if (activeFile == null) {
            return redirectToList("Unknown prompt '" + workerId + "/" + file + "'");
        }

        Optional<PromptOverride> latest = promptService.latest(workerId, activeFile);
        boolean overridden = latest.map(o -> !o.isUseDefault()).orElse(false);
        Integer activeVersion = overridden ? latest.get().getVersion() : null;

        Map<String, Object> reference = referenceColumn(workerId, activeFile, compare);

        model.addAttribute("workerId", workerId);
        model.addAttribute("promptNames", promptNames);
        model.addAttribute("activeFile", activeFile);
        model.addAttribute("overridden", overridden);
        model.addAttribute("activeVersion", activeVersion);
        model.addAttribute("referenceLabel", reference.get("label"));
        model.addAttribute("referenceContent", reference.get("content"));
        model.addAttribute("effectiveContent", promptService.resolve(workerId, activeFile));
        model.addAttribute("history", historyRows(workerId, activeFile, activeVersion, reference.get("version")));
        model.addAttribute("error", error);
        return "prompt";
    }

    @PostMapping("/prompts/{workerId}/save")
    public String save(@PathVariable("workerId") String workerId,
                       @RequestParam("file") String file,
                       @RequestParam(name = "content", required = false) String content,
                       @RequestParam(name = "author", required = false) String author,
                       RedirectAttributes redirect) {
        try {
            PromptOverride saved = promptService.saveOverride(workerId, file, content, author);
            redirect.addFlashAttribute("message", "Saved prompt version " + saved.getVersion());
            return "redirect:/prompts/" + workerId + "?file=" + urlEncode(file);
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            return redirectToEditor(workerId, file, e.getMessage());
        }
    }

    @PostMapping("/prompts/{workerId}/revert")
    public String revert(@PathVariable("workerId") String workerId,
                         @RequestParam("file") String file,
                         @RequestParam(name = "author", required = false) String author,
                         RedirectAttributes redirect) {
        try {
            promptService.revertToDefault(workerId, file, author);
            redirect.addFlashAttribute("message", "Reverted to default");
            return "redirect:/prompts/" + workerId + "?file=" + urlEncode(file);
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            return redirectToEditor(workerId, file, e.getMessage());
        }
    }

    private Map<String, Object> promptSummaryRow(String workerId, String promptName) {
        Optional<PromptOverride> latest = promptService.latest(workerId, promptName);
        boolean overridden = latest.map(o -> !o.isUseDefault()).orElse(false);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", promptName);
        row.put("overridden", overridden);
        row.put("activeVersion", overridden ? latest.get().getVersion() : null);
        row.put("updatedBy", overridden ? latest.get().getCreatedBy() : null);
        row.put("updatedAt", overridden ? format(latest.get().getCreatedAt()) : null);
        return row;
    }

    private String resolveFile(String file, List<String> promptNames) {
        if (file == null || file.isBlank()) {
            return promptNames.contains(DEFAULT_PROMPT) ? DEFAULT_PROMPT : promptNames.get(0);
        }
        return promptNames.contains(file) ? file : null;
    }

    private Map<String, Object> referenceColumn(String workerId, String file, String compare) {
        Map<String, Object> reference = new LinkedHashMap<>();
        Integer compareVersion = parseVersion(compare);
        if (compareVersion != null) {
            Optional<PromptOverride> version = promptService.version(workerId, file, compareVersion);
            if (version.isPresent()) {
                PromptOverride override = version.get();
                reference.put("version", override.getVersion());
                if (override.isUseDefault()) {
                    reference.put("label", "Version " + override.getVersion() + " (reverted to default)");
                    reference.put("content", promptService.defaultContent(workerId, file));
                } else {
                    reference.put("label", "Version " + override.getVersion());
                    reference.put("content", override.getContent());
                }
                return reference;
            }
        }
        reference.put("version", null);
        reference.put("label", "Bundled default");
        reference.put("content", promptService.defaultContent(workerId, file));
        return reference;
    }

    private List<Map<String, Object>> historyRows(String workerId, String file, Integer activeVersion,
                                                  Object comparedVersion) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PromptOverride override : promptService.history(workerId, file)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", override.getVersion());
            row.put("author", override.getCreatedBy());
            row.put("createdAt", format(override.getCreatedAt()));
            row.put("useDefault", override.isUseDefault());
            row.put("compareHref", "/prompts/" + workerId + "?file=" + urlEncode(file)
                    + "&compare=" + override.getVersion());
            row.put("current", activeVersion != null && activeVersion == override.getVersion());
            row.put("comparing", comparedVersion != null
                    && comparedVersion.equals(override.getVersion()));
            rows.add(row);
        }
        return rows;
    }

    private static String format(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant);
    }

    private static Integer parseVersion(String compare) {
        if (compare == null || compare.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(compare.strip());
        } catch (NumberFormatException notAVersion) {
            return null;
        }
    }

    private String redirectToList(String message) {
        return "redirect:/prompts?error=" + urlEncode(message);
    }

    private String redirectToEditor(String workerId, String file, String message) {
        return "redirect:/prompts/" + workerId + "?file=" + urlEncode(file)
                + "&error=" + urlEncode(message == null ? "Request failed" : message);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
