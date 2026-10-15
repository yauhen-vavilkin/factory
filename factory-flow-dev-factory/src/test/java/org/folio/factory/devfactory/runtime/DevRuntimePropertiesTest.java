package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevRuntimePropertiesTest {
    @Test
    void independentProfilesKeepPiLegacyFallbacksAndExactClaudeModel() {
        var values = new java.util.HashMap<String, Object>();
        String prefix = "factory.dev-factory.runtime.coding.";
        values.put(prefix + "runtime", "claude-code");
        values.put(prefix + "image", "legacy:image");
        values.put(prefix + "provider", "legacy-provider");
        values.put(prefix + "model", "legacy-model");
        values.put(prefix + "api-key", "legacy-key");
        values.put(prefix + "pi.model", "new-pi-model");
        values.put(prefix + "pi.api-key", "new-pi-key");
        values.put(prefix + "pi.base-url", "");
        values.put(prefix + "pi.max-output-tokens", "8192");
        values.put(prefix + "claude-code.model", "operator/Custom-Model-2026");
        values.put(prefix + "claude-code.base-url", "http://gateway.example/anthropic");
        values.put(prefix + "claude-code.authentication", "bearer-token");
        values.put(prefix + "claude-code.auth-token", "claude-secret");
        var coding = bind(values).coding();
        assertThat(coding.piProfile().image()).isEqualTo("legacy:image");
        assertThat(coding.piProfile().provider()).isEqualTo("legacy-provider");
        assertThat(coding.piProfile().model()).isEqualTo("new-pi-model");
        assertThat(coding.piProfile().apiKey()).isEqualTo("new-pi-key");
        assertThat(coding.piProfile().maxOutputTokens()).isEqualTo(8192);
        assertThat(coding.claudeCode().model()).isEqualTo("operator/Custom-Model-2026");
        coding.claudeCode().requireConfigured();
        assertThat(coding.toString() + coding.piProfile() + coding.claudeCode())
                .doesNotContain("legacy-key", "new-pi-key", "claude-secret", "gateway.example");
        var selected = new org.folio.factory.devfactory.DevFactoryConfiguration().devCodingRuntime(bind(values));
        assertThat(selected).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(selected.identity()).containsEntry("model", "operator/Custom-Model-2026");
    }

    @Test
    void inactiveProfilesAreNotValidatedAndSelectionIsClosed() {
        var configuration = new org.folio.factory.devfactory.DevFactoryConfiguration();
        var empty = bind(Map.of("factory.dev-factory.runtime.coding.runtime", "claude-code",
                "factory.dev-factory.runtime.coding.pi.max-output-tokens", "-1"));
        assertThat(configuration.devCodingRuntime(empty)).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(configuration.devCodingRuntime(new DevRuntimeProperties(null, null, 0, null)))
                .isInstanceOf(PiCodingRuntime.class);
        assertThatThrownBy(() -> configuration.devCodingRuntime(bind(Map.of(
                "factory.dev-factory.runtime.coding.runtime", "unknown"))))
                .hasMessageContaining("Unsupported coding runtime");
    }

    @Test
    void claudeRejectsWrongProtocolAndRequiresSelectedCredentialBeforeExecution() {
        var config = new DevRuntimeProperties.ClaudeCode(null, "openai-completions", "http://gateway",
                "model", "api-key", "secret", null, null, null, null);
        var workload = org.mockito.Mockito.mock(DockerWorkloads.Workload.class);
        assertThatThrownBy(() -> new ClaudeCodeCodingRuntime(config).code(workload, null, 30))
                .hasMessageContaining("api=anthropic-messages");
        org.mockito.Mockito.verifyNoInteractions(workload);
        for (String auth : new String[] {null, "", "oauth", "bearer-token"}) {
            var missing = new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway", "model",
                    auth, "api-key-is-not-a-token", null, null, null, null);
            assertThatThrownBy(missing::requireConfigured).isInstanceOf(IllegalStateException.class);
        }
    }

    private static DevRuntimeProperties bind(Map<String, ?> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
    }

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
    void bindsPlainOpenAiCompatibleProfileWithUnchangedDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.coding.image", "pi:image",
                "factory.dev-factory.runtime.coding.provider", "openai-compatible",
                "factory.dev-factory.runtime.coding.model", "test-model",
                "factory.dev-factory.runtime.coding.base-url", "https://api.example.com/v1",
                "factory.dev-factory.runtime.coding.api-key", "test-key"));
        var coding = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().coding();
        assertThat(coding.provider()).isEqualTo("openai-compatible");
        assertThat(coding.model()).isEqualTo("test-model");
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

    @Test
    void bindsTrustedRequiredReportPathsAndRejectsTraversal() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.required-reports.roles[0]",
                "target/failsafe-reports/TEST-org.folio.roles.it.CacheConfigIT.xml"));
        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        assertThat(properties.requiredReports("roles")).containsExactly(
                "target/failsafe-reports/TEST-org.folio.roles.it.CacheConfigIT.xml");
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, null,
                Map.of("roles", java.util.List.of("../target/failsafe-reports/TEST-a.xml"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
