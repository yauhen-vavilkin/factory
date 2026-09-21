package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevRuntimePropertiesTest {
    @Test
    void bindsCodingSettingsFromSpringConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.timeout-seconds", "60",
                "factory.dev-factory.runtime.coding.image", "coding:image",
                "factory.dev-factory.runtime.coding.provider", "provider",
                "factory.dev-factory.runtime.coding.model", "model",
                "factory.dev-factory.runtime.coding.base-url", "https://example.test/v1",
                "factory.dev-factory.runtime.coding.api", "openai-responses",
                "factory.dev-factory.runtime.coding.api-key", "test-key",
                "factory.dev-factory.runtime.coding.runtime", "OTHER"));

        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        var coding = properties.coding();
        assertThat(coding.image()).isEqualTo("coding:image");
        assertThat(coding.provider()).isEqualTo("provider");
        assertThat(coding.model()).isEqualTo("model");
        assertThat(coding.baseUrl()).isEqualTo("https://example.test/v1");
        assertThat(coding.api()).isEqualTo("openai-responses");
        assertThat(coding.apiKey()).isEqualTo("test-key");
        assertThat(coding.runtime()).isEqualTo("other");
        coding.requireConfigured();
    }

    @Test
    void mavenCacheHasStableDefaultAndAcceptsOperatorOverride() {
        var defaults = new DevRuntimeProperties(null, null, 0, null);
        assertThat(defaults.mavenCacheVolume()).isEqualTo("factory-dev-m2-cache");
        assertThat(defaults.coding().runtime()).isEqualTo("pi");
        assertThat(new DevRuntimeProperties(null, null, 0, "operator-cache").mavenCacheVolume())
                .isEqualTo("operator-cache");
    }

    @Test
    void codingRuntimeSelectionIsNormalizedForAdapterLookup() {
        var coding = new DevRuntimeProperties.Coding("image", "provider", "model", null, null, "key", " Pi ");
        assertThat(coding.runtime()).isEqualTo("pi");
    }

    @Test
    void rejectsNamesThatCannotBeDockerVolumeNames() {
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, "bad/name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
