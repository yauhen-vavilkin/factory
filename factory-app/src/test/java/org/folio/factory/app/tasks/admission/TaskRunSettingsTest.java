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
            List.of("low", "medium", "high", "xhigh", "max"), "high", null, null, null);
    private static final ModelEntry EXAMPLE = new ModelEntry("example-large", "anthropic-messages",
            "https://api.example.com/anthropic", "bearer-token", "FACTORY_EXAMPLE_API_KEY", "example-large",
            List.of("low", "high", "max"), "max", null, null, null);

    @Test
    void theFormOffersReadyModelsAndStartsFromTheFirstWhenNoDefaultIsSet() {
        var form = settings("claude-code", "").form();

        assertThat(form.models()).extracting(TaskRunSettings.ModelOption::id).containsExactly("codemie-sonnet-5.5");
        assertThat(form.modelListShown()).isFalse();
        assertThat(form.selected().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(form.effort()).isEqualTo("high");
        assertThat(form.selected().effortList()).isEqualTo("low medium high xhigh max");
        assertThat(form.effortHint())
                .isEqualTo("Effort is how long the model thinks at each step: higher is slower and costs more.");
    }

    @Test
    void theFormStartsFromTheConfiguredDefaultModelAndItsDefaultEffort() {
        CodingRuntime coding = mock(CodingRuntime.class);
        when(coding.id()).thenReturn("claude-code");
        var secrets = Map.of("FACTORY_EXAMPLE_API_KEY", "example-key", "FACTORY_CODEMIE_PROXY_TOKEN", "codemie-proxy");
        var settings = new TaskRunSettings(coding, new ModelCatalog(
                new ModelCatalog.Properties(List.of(SONNET, EXAMPLE), "example-large"), "claude-code", secrets::get));

        var form = settings.form();

        assertThat(form.modelListShown()).isTrue();
        assertThat(form.selected().id()).isEqualTo("example-large");
        assertThat(form.effort()).isEqualTo("max");
    }

    @Test
    void withoutAReadyModelTheFormSaysWhyAndARunIsRefused() {
        var settings = settings("claude-code", "", "");

        var form = settings.form();

        assertThat(form.selected()).isNull();
        assertThat(form.problem()).isEqualTo("No model can run now. The Configuration tab lists the models in"
                + " config/developer-models.yaml and what each one needs.");
        assertThatThrownBy(() -> settings.snapshot(null, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model codemie-sonnet-5.5 is not configured");
    }

    @Test
    void withPiThereIsNoModelChoiceAndARunKeepsOnlyItsInstructions() {
        var settings = settings("pi", "example-key");

        assertThat(settings.form()).isNull();
        assertThat(settings.snapshot("example-large", "max", null)).isNull();
        assertThat(TaskRunSettings.settings(settings.snapshot("example-large", "max", " Keep the API. ")))
                .isEqualTo(new CodingRequest.RunSettings(null, null, "Keep the API."));
    }

    @Test
    void theSnapshotHoldsTheChoiceButNeverTheSecret() {
        String snapshot = settings("claude-code", "example-key").snapshot("example-large", "", "Note");

        assertThat(TaskRunSettings.settings(snapshot)).isEqualTo(new CodingRequest.RunSettings("example-large", "max",
                "Note"));
        assertThat(snapshot).doesNotContain("example-key", "api.example.com", "maxTurns");
        assertThat(TaskRunSettings.label(snapshot)).isEqualTo("example-large · max");
        assertThat(TaskRunSettings.label(null)).isNull();
        assertThat(TaskRunSettings.settings(null)).isNull();
    }

    @Test
    void snapshotsStoredBeforeTheModelsFileStillRead() {
        String stored = "{\"effort\":\"low\",\"endpointOrigin\":\"https://api.example.com\","
                + "\"instructions\":\"Keep the API.\",\"maxTurns\":77,\"model\":\"example-large\","
                + "\"profileId\":\"example-large\",\"profileLabel\":\"Example Large (Example)\"}";

        assertThat(TaskRunSettings.settings(stored))
                .isEqualTo(new CodingRequest.RunSettings("example-large", "low", "Keep the API."));
        assertThat(TaskRunSettings.label(stored)).isEqualTo("example-large · low");
        assertThat(TaskRunSettings.label("{\"profileId\":\"env-default\"}")).isEqualTo("env-default");
        assertThat(TaskRunSettings.instructions(stored)).isEqualTo("Keep the API.");
    }

    private static TaskRunSettings settings(String runtime, String exampleKey) {
        return settings(runtime, exampleKey, "codemie-proxy");
    }

    private static TaskRunSettings settings(String runtime, String exampleKey, String proxyToken) {
        CodingRuntime coding = mock(CodingRuntime.class);
        when(coding.id()).thenReturn(runtime);
        var secrets = Map.of("FACTORY_EXAMPLE_API_KEY", exampleKey, "FACTORY_CODEMIE_PROXY_TOKEN", proxyToken);
        return new TaskRunSettings(coding, new ModelCatalog(new ModelCatalog.Properties(List.of(SONNET, EXAMPLE), null),
                runtime, secrets::get));
    }
}
