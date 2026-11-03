package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The models a person chooses from when starting a run, read from config/developer-models.yaml. Which of them
 * a run form offers depends on the installed harness: Claude Code runs anthropic-messages models. Pi takes its
 * model from FACTORY_PI_* and uses this file only for prices.
 */
public final class ModelCatalog {
    public static final String FILE = "config/developer-models.yaml";
    /** Runs started before 2026-11-03 can name this profile; it means the default model. */
    public static final String LEGACY_DEFAULT = "env-default";
    public static final int MAX_INSTRUCTIONS = 8000;

    private final Map<String, Model> models = new LinkedHashMap<>();
    private final List<String> secrets = new ArrayList<>();
    private final boolean claudeCode;
    private final Model defaultModel;

    /** A run cannot use its model. The message is safe to show: it names the model and what to fix. */
    public static final class ModelException extends IllegalStateException {
        public ModelException(String message) {
            super(message);
        }
    }

    @ConfigurationProperties(prefix = "factory.dev-factory")
    public record Properties(List<ModelEntry> models, String defaultModel) {
    }

    /** What one run uses; instructions may be null. */
    public record Run(Model model, String effort, String instructions) {
    }

    public ModelCatalog(Properties file, String harness, Function<String, String> variables) {
        claudeCode = ClaudeCodeCodingRuntime.ID.equals(harness);
        List<ModelEntry> entries = file == null || file.models() == null ? List.of() : file.models();
        for (ModelEntry entry : entries) {
            Model model = entry.toModel(variables);
            if (models.putIfAbsent(model.id(), model) != null) {
                throw new IllegalArgumentException(FILE + ": model id " + model.id()
                        + " is used twice; give each model its own id.");
            }
            if (model.hasSecret()) {
                secrets.add(model.secret());
            }
        }
        defaultModel = chooseDefault(file == null ? null : file.defaultModel());
    }

    public List<Model> all() {
        return List.copyOf(models.values());
    }

    public Optional<Model> find(String id) {
        return Optional.ofNullable(id == null ? null : models.get(id));
    }

    /**
     * The model of runs that name none: FACTORY_DEV_FACTORY_DEFAULT_MODEL, or else the first model in the file
     * that the harness can run. Empty with Pi, or when no model suits Claude Code.
     */
    public Optional<Model> defaultModel() {
        return Optional.ofNullable(defaultModel);
    }

    /** The models a run form offers: the harness can run them and their secret is set. */
    public List<Model> ready() {
        return models.values().stream().filter(model -> problem(model).isEmpty()).toList();
    }

    /** Why this model cannot run with the installed harness now, said so that a person can fix it. */
    public Optional<String> problem(Model model) {
        if (!claudeCode) {
            return Optional.of("Pi takes its model from FACTORY_PI_MODEL; this file gives Pi only prices.");
        }
        if (!ClaudeCodeCodingRuntime.API.equals(model.api())) {
            return Optional.of("Model " + model.id() + " uses api " + model.api()
                    + ", but Claude Code understands only " + ClaudeCodeCodingRuntime.API + ".");
        }
        var unknown = model.efforts().stream().filter(effort -> !ClaudeCodeCodingRuntime.EFFORTS.contains(effort))
                .toList();
        if (!unknown.isEmpty()) {
            return Optional.of("Model " + model.id() + " lists effort " + String.join(", ", unknown)
                    + ", but Claude Code accepts only " + String.join(", ", ClaudeCodeCodingRuntime.EFFORTS) + ".");
        }
        if (!model.hasSecret()) {
            // Compose passes only the variables it lists to Factory, so a new secret-env must be listed too.
            return Optional.of("Model " + model.id() + " is not configured: set " + model.secretVariable()
                    + " in .env. If docker-compose.yml does not pass " + model.secretVariable()
                    + " to the factory service yet, add it there. Then run docker compose up -d again.");
        }
        return Optional.empty();
    }

    /**
     * Checks a run form: the model must be ready and the effort one of its levels. A blank model or effort takes
     * the default. Bad input throws IllegalArgumentException with a message that says what to fix.
     */
    public Run choose(String modelId, String effort, String instructions) {
        Model model = modelId == null || modelId.isBlank() ? defaultModel : models.get(modelId.strip());
        if (model == null) {
            throw new IllegalArgumentException(modelId == null || modelId.isBlank() ? noDefaultMessage()
                    : "There is no model " + modelId.strip() + ". Reload the page and choose a model from the list.");
        }
        problem(model).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        String chosen = effort == null || effort.isBlank() ? model.defaultEffort() : effort.strip();
        if (!model.efforts().contains(chosen)) {
            throw new IllegalArgumentException("Effort " + chosen + " is not available for model " + model.id()
                    + ". Choose one of: " + String.join(", ", model.efforts()) + ".");
        }
        return new Run(model, chosen, instructions(instructions));
    }

    /**
     * The model and effort a stored run uses. No model, or the old env-default profile, means the default model;
     * no effort means the model's default. It does not check the harness or the secret: see {@link #problem}.
     */
    public Run resolve(CodingRequest.RunSettings settings) {
        String id = settings == null ? null : settings.modelId();
        boolean useDefault = id == null || id.isBlank() || LEGACY_DEFAULT.equals(id);
        Model model = useDefault ? defaultModel : models.get(id);
        if (model == null) {
            throw new ModelException(useDefault ? noDefaultMessage() : "This run uses model " + id
                    + ", which is not in " + FILE + " any more. Add it back, or retry the task with another model.");
        }
        String effort = settings == null || settings.effort() == null || settings.effort().isBlank()
                ? model.defaultEffort() : settings.effort();
        if (!model.efforts().contains(effort)) {
            throw new ModelException("Model " + model.id() + " no longer allows effort " + effort + ". Fix " + FILE
                    + ", or retry the task with another effort.");
        }
        return new Run(model, effort, settings == null ? null : settings.instructions());
    }

    /**
     * The entry whose price a run pays, when that entry has a price: by entry id, or by the model string when the
     * run records no entry id (Pi runs and runs started before 2026-11-03) and exactly one entry has that string.
     * An id that is no longer in the file has no price: another entry with the same model string may be another
     * provider with another price.
     */
    public Optional<Model> pricedEntry(String modelId, String model) {
        if (modelId != null && !modelId.isBlank()) {
            return find(modelId).filter(entry -> entry.price() != null);
        }
        var matches = models.values().stream().filter(entry -> entry.model().equals(model)).toList();
        return matches.size() == 1 && matches.getFirst().price() != null
                ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** Replaces the secret of every model, ready or not. */
    public String redact(String value) {
        if (value == null) {
            return null;
        }
        for (String secret : secrets) {
            value = value.replace(secret, "[REDACTED]");
        }
        return value;
    }

    /** Instructions for the agent are optional plain text of at most 8000 characters; blank means none. */
    public static String instructions(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String instructions = text.strip();
        if (instructions.length() > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("Instructions for the agent can have at most " + MAX_INSTRUCTIONS
                    + " characters; this text has " + instructions.length() + ". Shorten it and try again.");
        }
        return instructions;
    }

    private Model chooseDefault(String requested) {
        if (requested != null && !requested.isBlank()) {
            return find(requested.strip()).orElseThrow(() -> new IllegalArgumentException(
                    "FACTORY_DEV_FACTORY_DEFAULT_MODEL is " + requested.strip() + ", but " + FILE
                            + " has no model with that id. Leave it empty or use an id from the file."));
        }
        return claudeCode ? models.values().stream()
                .filter(model -> ClaudeCodeCodingRuntime.API.equals(model.api())).findFirst().orElse(null) : null;
    }

    private static String noDefaultMessage() {
        return "No model in " + FILE + " can run with Claude Code. Add a model with api "
                + ClaudeCodeCodingRuntime.API + "; Check configuration shows what each model needs.";
    }
}
