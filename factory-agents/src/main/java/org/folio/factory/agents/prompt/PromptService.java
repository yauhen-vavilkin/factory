package org.folio.factory.agents.prompt;

import org.folio.factory.agents.llm.PromptLoader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Resolves and versions prompt overrides. Classpath files remain the defaults;
 * the newest override row per (workerId, promptName) decides what is active, and
 * a {@code useDefault} row reverts to the bundled default. All history is
 * insert-only.
 */
@Service
public class PromptService implements PromptResolver {

    private final PromptOverrideRepository repository;
    private final PromptCatalog catalog;

    public PromptService(PromptOverrideRepository repository, PromptCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    @Override
    public String resolve(String workerId, String promptName) {
        return latest(workerId, promptName)
                .filter(override -> !override.isUseDefault())
                .map(PromptOverride::getContent)
                .orElseGet(() -> PromptLoader.load(workerId, promptName));
    }

    public String defaultContent(String workerId, String promptName) {
        return PromptLoader.load(workerId, promptName);
    }

    public Optional<PromptOverride> latest(String workerId, String promptName) {
        return repository.findTopByWorkerIdAndPromptNameOrderByVersionDesc(workerId, promptName);
    }

    public List<PromptOverride> history(String workerId, String promptName) {
        return repository.findByWorkerIdAndPromptNameOrderByVersionDesc(workerId, promptName);
    }

    public Optional<PromptOverride> version(String workerId, String promptName, int version) {
        return repository.findByWorkerIdAndPromptNameAndVersion(workerId, promptName, version);
    }

    @Transactional
    public PromptOverride saveOverride(String workerId, String promptName, String content, String author) {
        requireKnown(workerId, promptName);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Prompt content must not be blank");
        }
        requireAuthor(author);
        int nextVersion = nextVersion(workerId, promptName);
        return insert(new PromptOverride(workerId, promptName, nextVersion, false, content, author));
    }

    @Transactional
    public PromptOverride revertToDefault(String workerId, String promptName, String author) {
        requireKnown(workerId, promptName);
        requireAuthor(author);
        Optional<PromptOverride> latest = latest(workerId, promptName);
        if (latest.isEmpty() || latest.get().isUseDefault()) {
            throw new IllegalStateException("Default prompt is already in effect for " + workerId + "/" + promptName);
        }
        return insert(new PromptOverride(workerId, promptName, latest.get().getVersion() + 1, true, null, author));
    }

    private PromptOverride insert(PromptOverride override) {
        try {
            return repository.save(override);
        } catch (DataIntegrityViolationException concurrentEditor) {
            throw new IllegalStateException("Prompt was modified concurrently; reload and retry", concurrentEditor);
        }
    }

    private int nextVersion(String workerId, String promptName) {
        return latest(workerId, promptName).map(PromptOverride::getVersion).orElse(0) + 1;
    }

    private void requireKnown(String workerId, String promptName) {
        if (!catalog.exists(workerId, promptName)) {
            throw new NoSuchElementException("Unknown prompt " + workerId + "/" + promptName);
        }
    }

    private void requireAuthor(String author) {
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("Author must not be blank");
        }
    }
}
