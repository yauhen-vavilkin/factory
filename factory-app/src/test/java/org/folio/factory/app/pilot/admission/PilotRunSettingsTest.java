package org.folio.factory.app.pilot.admission;

import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PilotRunSettingsTest {

    private static final DevRuntimeProperties.ClaudeCode ENV = new DevRuntimeProperties.ClaudeCode(null, null,
            "http://gateway.example:4001", "claude-sonnet-5-5", "bearer-token", null, "gateway-token", 40, 16384,
            null);

    @Test
    void aProfileWithoutItsSecretIsListedAsNotConfiguredAndTheFormFallsBackToTheDefault() {
        var settings = settings("claude-code", "");
        String previous = "{\"profileId\":\"glm-5.3\",\"effort\":\"low\",\"maxTurns\":7,\"instructions\":\"x\"}";

        Map<String, Object> form = settings.form(previous);

        assertThat(rows(form, "profiles")).extracting(row -> row.get("label"))
                .containsExactly("Configured in .env", "GLM-5.3 (Z.ai) (not configured)");
        assertThat(rows(form, "profiles")).extracting(row -> row.get("configured")).containsExactly(true, false);
        assertThat(form).containsEntry("profile", "env-default").containsEntry("effort", "")
                .containsEntry("maxTurns", 40);
        assertThat(rows(form, "efforts")).first()
                .isEqualTo(Map.of("profile", "env-default", "value", "", "label", "high (CLI default)"));
    }

    @Test
    void withPiThereIsNoProfileChoiceAndARunKeepsOnlyItsInstructions() {
        var settings = settings("pi", "zai-key");

        assertThat(settings.form(null)).isNull();
        assertThat(settings.snapshot("glm-5.3", "max", "10", null)).isNull();
        assertThat(PilotRunSettings.settings(settings.snapshot("glm-5.3", "max", "10", " Keep the API. ")))
                .isEqualTo(new CodingRequest.RunSettings(null, null, null, "Keep the API."));
    }

    @Test
    void theSnapshotHoldsTheDisplayFieldsButNeverTheSecret() {
        String snapshot = settings("claude-code", "zai-key").snapshot("glm-5.3", "", "", "Note");

        assertThat(PilotRunSettings.read(snapshot)).isEqualTo(new PilotRunSettings.Snapshot("glm-5.3",
                "GLM-5.3 (Z.ai)", "glm-5.3", "https://api.z.ai", "max", 100, "Note"));
        assertThat(snapshot).doesNotContain("zai-key", "/api/anthropic");
        assertThat(PilotRunSettings.label(snapshot)).isEqualTo("glm-5.3 · max");
        assertThat(PilotRunSettings.label(null)).isNull();
        assertThat(PilotRunSettings.settings(null)).isNull();
    }

    private static PilotRunSettings settings(String runtime, String zaiKey) {
        CodingRuntime coding = mock(CodingRuntime.class);
        when(coding.id()).thenReturn(runtime);
        var glm = new RunProfiles.FileProfile("glm-5.3", "GLM-5.3 (Z.ai)", "https://api.z.ai/api/anthropic",
                "bearer-token", "FACTORY_ZAI_API_KEY", "glm-5.3", List.of("low", "high", "max"), "max", 100, 16384);
        return new PilotRunSettings(coding, new RunProfiles(ENV, new RunProfiles.Properties(List.of(glm), null),
                Map.of("FACTORY_ZAI_API_KEY", zaiKey)::get));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> form, String key) {
        return (List<Map<String, Object>>) form.get(key);
    }
}
