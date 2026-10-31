package org.folio.factory.app.tasks.admission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run settings of a task attempt: what a run form chose (profile, effort, max turns, instructions),
 * checked against the profile and stored on the attempt as a snapshot. The run uses the profile the
 * snapshot names. Profiles apply to the Claude Code runtime only: with Pi a run keeps only its instructions.
 */
@Component
@RequiredArgsConstructor
public class TaskRunSettings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CodingRuntime coding;
    private final RunProfiles profiles;

    /** Snapshots stored before 2026-10-31 also hold profileLabel, model and endpointOrigin; reading skips them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(String profileId, String effort, Integer maxTurns, String instructions) {
    }

    public boolean profilesApply() {
        return "claude-code".equals(coding.id());
    }

    /**
     * Checks a run form and returns the snapshot to store, or null when nothing was chosen. Bad input throws
     * IllegalArgumentException with a message that says what to fix.
     */
    public String snapshot(String profileId, String effort, String maxTurns, String instructions) {
        if (!profilesApply()) {
            String text = RunProfiles.instructions(instructions);
            return text == null ? null : JSON.writeValueAsString(new Snapshot(null, null, null, text));
        }
        RunProfiles.Run run = profiles.choose(profileId, effort, maxTurns, instructions);
        return JSON.writeValueAsString(new Snapshot(run.profile().id(), run.effort(), run.maxTurns(),
                run.instructions()));
    }

    public static Snapshot read(String snapshot) {
        return snapshot == null || snapshot.isBlank() ? null : JSON.readValue(snapshot, Snapshot.class);
    }

    /** What the run passes to the coding request; null runs with the defaults. */
    public static CodingRequest.RunSettings settings(String snapshot) {
        Snapshot stored = read(snapshot);
        return stored == null ? null : new CodingRequest.RunSettings(stored.profileId(), stored.effort(),
                stored.maxTurns(), stored.instructions());
    }

    /** "glm-5.3 · max" for the Attempts table; null when the attempt names no profile. */
    public static String label(String snapshot) {
        Snapshot stored = read(snapshot);
        if (stored == null || stored.profileId() == null) {
            return null;
        }
        return stored.profileId() + " · " + RunProfiles.effortLabel(stored.effort());
    }

    /**
     * The Profile, Effort and Max turns choices of a run form, filled from a previous snapshot when that
     * profile can still run, otherwise from the default profile. Null with the Pi runtime, which shows no
     * profile choice. Effort options carry their profile so the page script can show only the chosen
     * profile's options.
     */
    public Map<String, Object> form(String previous) {
        if (!profilesApply()) {
            return null;
        }
        Snapshot stored = read(previous);
        RunProfiles.Profile selected = stored == null ? null : profiles.find(stored.profileId())
                .filter(RunProfiles.Profile::configured).orElse(null);
        if (selected == null) {
            selected = profiles.defaultProfile();
            stored = null;
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("profiles", profiles.all().stream().map(TaskRunSettings::option).toList());
        form.put("efforts", profiles.all().stream().flatMap(profile -> effortChoices(profile).stream()).toList());
        form.put("profile", selected.id());
        String effort = stored == null ? selected.defaultEffort() : stored.effort();
        form.put("effort", value(effort == null || selected.efforts().contains(effort) ? effort
                : selected.defaultEffort()));
        form.put("maxTurns", stored == null || stored.maxTurns() == null ? selected.maxTurns() : stored.maxTurns());
        form.put("maxTurnsLimit", RunProfiles.MAX_TURNS);
        return form;
    }

    /** The instructions of a previous snapshot, for the task page's Retry form. */
    public static String instructions(String previous) {
        Snapshot stored = read(previous);
        return stored == null ? null : stored.instructions();
    }

    private static Map<String, Object> option(RunProfiles.Profile profile) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", profile.id());
        option.put("label", profile.configured() ? profile.label() : profile.label() + " (not configured)");
        option.put("configured", profile.configured());
        option.put("defaultEffort", value(profile.defaultEffort()));
        option.put("maxTurns", profile.maxTurns());
        return option;
    }

    /** An empty choice first when the profile has no default effort, then each effort the profile allows. */
    private static List<Map<String, Object>> effortChoices(RunProfiles.Profile profile) {
        List<Map<String, Object>> choices = new ArrayList<>();
        if (profile.defaultEffort() == null) {
            choices.add(effort(profile, ""));
        }
        profile.efforts().forEach(effort -> choices.add(effort(profile, effort)));
        return choices;
    }

    private static Map<String, Object> effort(RunProfiles.Profile profile, String value) {
        return Map.of("profile", profile.id(), "value", value,
                "label", RunProfiles.effortLabel(value.isEmpty() ? null : value));
    }

    private static String value(String effort) {
        return effort == null ? "" : effort;
    }
}
