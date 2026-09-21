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
                "factory.dev-factory.runtime.coding.provider", "codemie",
                "factory.dev-factory.runtime.coding.model", "gemini-3.8-flash",
                "factory.dev-factory.runtime.coding.base-url", "http://host.docker.internal:4001/v1",
                "factory.dev-factory.runtime.coding.api", "openai-completions",
                "factory.dev-factory.runtime.coding.api-key", "test-key",
                "factory.dev-factory.runtime.coding.runtime", "OTHER",
                "factory.dev-factory.runtime.coding.reasoning-effort", "high",
                "factory.dev-factory.runtime.coding.max-output-tokens", "65536"));

        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        var coding = properties.coding();
        assertThat(coding.image()).isEqualTo("coding:image");
        assertThat(coding.provider()).isEqualTo("codemie");
        assertThat(coding.model()).isEqualTo("gemini-3.8-flash");
        assertThat(coding.baseUrl()).isEqualTo("http://host.docker.internal:4001/v1");
        assertThat(coding.api()).isEqualTo("openai-completions");
        assertThat(coding.apiKey()).isEqualTo("test-key");
        assertThat(coding.runtime()).isEqualTo("other");
        assertThat(coding.reasoningEffort()).isEqualTo("high");
        assertThat(coding.maxOutputTokens()).isEqualTo(65536);
        coding.requireConfigured();
    }

    @Test
    void bindsExistingGlmProfileWithUnchangedDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.coding.image", "pi:image",
                "factory.dev-factory.runtime.coding.provider", "openai-compatible",
                "factory.dev-factory.runtime.coding.model", "glm-5.3",
                "factory.dev-factory.runtime.coding.base-url", "https://api.z.ai/api/coding/paas/v4",
                "factory.dev-factory.runtime.coding.api-key", "test-key"));
        var coding = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().coding();
        assertThat(coding.provider()).isEqualTo("openai-compatible");
        assertThat(coding.model()).isEqualTo("glm-5.3");
        assertThat(coding.api()).isEqualTo("openai-completions");
        assertThat(coding.reasoningEffort()).isNull();
        assertThat(coding.maxOutputTokens()).isEqualTo(16384);
    }

    @Test
    void mavenCacheHasStableDefaultAndAcceptsOperatorOverride() {
        var defaults = new DevRuntimeProperties(null, null, 0, null);
        assertThat(defaults.mavenCacheVolume()).isEqualTo("factory-dev-m2-cache");
        assertThat(defaults.coding().runtime()).isEqualTo("pi");
        assertThat(defaults.coding().reasoningEffort()).isNull();
        assertThat(defaults.coding().maxOutputTokens()).isEqualTo(16384);
        assertThat(new DevRuntimeProperties(null, null, 0, "operator-cache").mavenCacheVolume())
                .isEqualTo("operator-cache");
    }

    @Test
    void codingRuntimeSelectionIsNormalizedForAdapterLookup() {
        var coding = new DevRuntimeProperties.Coding("image", "provider", "model", null, null, "key", " Pi ", null, null);
        assertThat(coding.runtime()).isEqualTo("pi");
    }

    @Test
    void rejectsNamesThatCannotBeDockerVolumeNames() {
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, "bad/name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
