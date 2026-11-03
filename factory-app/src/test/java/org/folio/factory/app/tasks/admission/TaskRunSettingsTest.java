package org.folio.factory.app.tasks.admission;

import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRunSettingsTest {

    private static final ModelEntry SONNET = new ModelEntry("codemie-sonnet-5.5", "anthropic-messages",
            "http://gateway.example:4001", "bearer-token", "FACTORY_CODEMIE_PROXY_TOKEN", "claude-sonnet-5-5",
            List.of("low", "medium", "high", "xhigh", "max"), "high", null, null);
    private static final ModelEntry GLM = new ModelEntry("glm-5.3", "anthropic-messages",
            "https://api.z.ai/api/anthropic", "bearer-token", "FACTORY_ZAI_API_KEY", "glm-5.3",
            List.of("low", "high", "max"), "max", null, null);

    @Test
    void theFormOffersReadyModelsAndFallsBackToTheDefaultWhenThePreviousModelIsNotReady() {
        var settings = settings("claude-code", "");
        String previous = "{\"profileId\":\"glm-5.3\",\"effort\":\"low\",\"maxTurns\":7,\"instructions\":\"x\"}";

        var form = settings.form(previous);

        assertThat(form.models()).extracting(TaskRunSettings.ModelOption::id).containsExactly("codemie-sonnet-5.5");
        assertThat(form.modelListShown()).isFalse();
        assertThat(form.selected().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(form.effort()).isEqualTo("high");
        assertThat(form.selected().effortList()).isEqualTo("low medium high xhigh max");
    }

    @Test
    void theRetryFormKeepsThePreviousModelAndItsEffort() {
        var form = settings("claude-code", "zai-key").form("{\"profileId\":\"glm-5.3\",\"effort\":\"low\"}");

        assertThat(form.modelListShown()).isTrue();
        assertThat(form.selected().id()).isEqualTo("glm-5.3");
        assertThat(form.effort()).isEqualTo("low");
        // A run from before the models file named env-default: the form starts from the default model.
        var legacy = settings("claude-code", "zai-key").form("{\"profileId\":\"env-default\",\"effort\":null}");
        assertThat(legacy.selected().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(legacy.effort()).isEqualTo("high");
    }

    @Test
    void withoutAReadyModelTheFormSaysWhyAndARunIsRefused() {
        var settings = settings("claude-code", "", "");

        var form = settings.form(null);

        assertThat(form.selected()).isNull();
        assertThat(form.problem()).isEqualTo("No model can run now. Check configuration lists the models in"
                + " config/developer-models.yaml and what each one needs.");
        assertThatThrownBy(() -> settings.snapshot(null, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model codemie-sonnet-5.5 is not configured");
    }

    @Test
    void withPiThereIsNoModelChoiceAndARunKeepsOnlyItsInstructions() {
        var settings = settings("pi", "zai-key");

        assertThat(settings.form(null)).isNull();
        assertThat(settings.snapshot("glm-5.3", "max", null)).isNull();
        assertThat(TaskRunSettings.settings(settings.snapshot("glm-5.3", "max", " Keep the API. ")))
                .isEqualTo(new CodingRequest.RunSettings(null, null, "Keep the API."));
    }

    @Test
    void theSnapshotHoldsTheChoiceButNeverTheSecret() {
        String snapshot = settings("claude-code", "zai-key").snapshot("glm-5.3", "", "Note");

        assertThat(TaskRunSettings.settings(snapshot)).isEqualTo(new CodingRequest.RunSettings("glm-5.3", "max",
                "Note"));
        assertThat(snapshot).doesNotContain("zai-key", "/api/anthropic", "maxTurns");
        assertThat(TaskRunSettings.label(snapshot)).isEqualTo("glm-5.3 · max");
        assertThat(TaskRunSettings.label(null)).isNull();
        assertThat(TaskRunSettings.settings(null)).isNull();
    }

    @Test
    void snapshotsStoredBeforeTheModelsFileStillRead() {
        String stored = "{\"effort\":\"low\",\"endpointOrigin\":\"https://api.z.ai\","
                + "\"instructions\":\"Keep the API.\",\"maxTurns\":77,\"model\":\"glm-5.3\","
                + "\"profileId\":\"glm-5.3\",\"profileLabel\":\"GLM-5.3 (Z.ai)\"}";

        assertThat(TaskRunSettings.settings(stored))
                .isEqualTo(new CodingRequest.RunSettings("glm-5.3", "low", "Keep the API."));
        assertThat(TaskRunSettings.label(stored)).isEqualTo("glm-5.3 · low");
        assertThat(TaskRunSettings.label("{\"profileId\":\"env-default\"}")).isEqualTo("env-default");
        assertThat(TaskRunSettings.instructions(stored)).isEqualTo("Keep the API.");
    }

    private static TaskRunSettings settings(String runtime, String zaiKey) {
        return settings(runtime, zaiKey, "codemie-proxy");
    }

    private static TaskRunSettings settings(String runtime, String zaiKey, String proxyToken) {
        CodingRuntime coding = mock(CodingRuntime.class);
        when(coding.id()).thenReturn(runtime);
        var secrets = Map.of("FACTORY_ZAI_API_KEY", zaiKey, "FACTORY_CODEMIE_PROXY_TOKEN", proxyToken);
        return new TaskRunSettings(coding, new ModelCatalog(new ModelCatalog.Properties(List.of(SONNET, GLM), null),
                runtime, secrets::get));
    }
}
