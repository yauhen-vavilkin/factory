package org.folio.factory.app.pilot.admission;

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
 * The run settings of a pilot attempt: what a run form chose (profile, effort, max turns, instructions),
 * checked against the profile and stored on the attempt as a snapshot. The run uses the profile the
 * snapshot names; model and endpoint origin in it are for display. Profiles apply to the Claude Code
 * runtime only: with Pi a run keeps only its instructions.
 */
@Component
public class PilotRunSettings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CodingRuntime coding;
    private final RunProfiles profiles;

    public PilotRunSettings(CodingRuntime coding, RunProfiles profiles) {
        this.coding = coding;
        this.profiles = profiles;
    }

    public record Snapshot(String profileId, String profileLabel, String model, String endpointOrigin,
                           String effort, Integer maxTurns, String instructions) {
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
            return text == null ? null : JSON.writeValueAsString(
                    new Snapshot(null, null, null, null, null, null, text));
        }
        RunProfiles.Run run = profiles.choose(profileId, effort, maxTurns, instructions);
        RunProfiles.Profile profile = run.profile();
        return JSON.writeValueAsString(new Snapshot(profile.id(), profile.label(), profile.model(),
                profile.endpointOrigin(), run.effort(), run.maxTurns(), run.instructions()));
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
        List<Map<String, Object>> options = new ArrayList<>();
        List<Map<String, Object>> efforts = new ArrayList<>();
        for (RunProfiles.Profile profile : profiles.all()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", profile.id());
            option.put("label", profile.configured() ? profile.label() : profile.label() + " (not configured)");
            option.put("configured", profile.configured());
            option.put("defaultEffort", value(profile.defaultEffort()));
            option.put("maxTurns", profile.maxTurns());
            options.add(option);
            if (profile.defaultEffort() == null) {
                efforts.add(effort(profile, ""));
            }
            profile.efforts().forEach(effort -> efforts.add(effort(profile, effort)));
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("profiles", options);
        form.put("efforts", efforts);
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

    private static Map<String, Object> effort(RunProfiles.Profile profile, String value) {
        return Map.of("profile", profile.id(), "value", value,
                "label", RunProfiles.effortLabel(value.isEmpty() ? null : value));
    }

    private static String value(String effort) {
        return effort == null ? "" : effort;
    }
}
