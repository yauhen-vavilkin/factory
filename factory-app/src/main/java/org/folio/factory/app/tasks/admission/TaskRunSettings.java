package org.folio.factory.app.tasks.admission;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.Model;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * The run settings of a task attempt: the model and effort a run form chose and optional instructions, checked
 * against config/developer-models.yaml and stored on the attempt as a snapshot. Models apply to the Claude Code
 * harness only: with Pi a run keeps only its instructions.
 */
@Component
@RequiredArgsConstructor
public class TaskRunSettings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CodingRuntime coding;
    private final ModelCatalog models;

    /**
     * The Model and Effort choices of a run form. models are the ready ones; selected is null when none is ready,
     * and problem then says why. effort is the effort to check first.
     */
    public record Form(List<ModelOption> models, ModelOption selected, String effort, String problem) {
        /** With one ready model the list is hidden and the form sends its id. */
        public boolean modelListShown() {
            return models.size() > 1;
        }
    }

    /** One model of a run form; the page script reads its efforts to rebuild the Effort choices. */
    public record ModelOption(String id, List<String> efforts, String defaultEffort) {
        public String effortList() {
            return String.join(" ", efforts);
        }
    }

    public boolean modelsApply() {
        return ClaudeCodeCodingRuntime.ID.equals(coding.id());
    }

    /**
     * Checks a run form and returns the snapshot to store, or null when nothing was chosen. Bad input throws
     * IllegalArgumentException with a message that says what to fix.
     */
    public String snapshot(String modelId, String effort, String instructions) {
        if (!modelsApply()) {
            String text = ModelCatalog.instructions(instructions);
            return text == null ? null : JSON.writeValueAsString(new CodingRequest.RunSettings(null, null, text));
        }
        ModelCatalog.Run run = models.choose(modelId, effort, instructions);
        return JSON.writeValueAsString(new CodingRequest.RunSettings(run.model().id(), run.effort(),
                run.instructions()));
    }

    /** What the run passes to the coding request; null runs with the defaults. */
    public static CodingRequest.RunSettings settings(String snapshot) {
        return snapshot == null || snapshot.isBlank() ? null
                : JSON.readValue(snapshot, CodingRequest.RunSettings.class);
    }

    /** "glm-5.3 · max" for the Attempts table; null when the attempt names no model. */
    public static String label(String snapshot) {
        CodingRequest.RunSettings stored = settings(snapshot);
        if (stored == null || stored.modelId() == null) {
            return null;
        }
        return stored.effort() == null ? stored.modelId() : stored.modelId() + " · " + stored.effort();
    }

    /**
     * The run form, filled from a previous snapshot when its model is still ready, otherwise from the default
     * model, or else the first ready one. Null with the Pi harness, which shows no model choice.
     */
    public Form form(String previous) {
        if (!modelsApply()) {
            return null;
        }
        List<ModelOption> options = models.ready().stream().map(TaskRunSettings::option).toList();
        CodingRequest.RunSettings stored = settings(previous);
        ModelOption selected = find(options, stored == null ? null : stored.modelId())
                .or(() -> find(options, models.defaultModel().map(Model::id).orElse(null)))
                .orElseGet(() -> options.isEmpty() ? null : options.getFirst());
        if (selected == null) {
            return new Form(options, null, null, "No model can run now. Check configuration lists the models in "
                    + ModelCatalog.FILE + " and what each one needs.");
        }
        boolean sameModel = stored != null && selected.id().equals(stored.modelId());
        String effort = sameModel && selected.efforts().contains(stored.effort()) ? stored.effort()
                : selected.defaultEffort();
        return new Form(options, selected, effort, null);
    }

    /** The instructions of a previous snapshot, for the task page's Retry form. */
    public static String instructions(String previous) {
        CodingRequest.RunSettings stored = settings(previous);
        return stored == null ? null : stored.instructions();
    }

    private static Optional<ModelOption> find(List<ModelOption> options, String id) {
        return options.stream().filter(option -> option.id().equals(id)).findFirst();
    }

    private static ModelOption option(Model model) {
        return new ModelOption(model.id(), model.efforts(), model.defaultEffort());
    }
}
