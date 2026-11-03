package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevRuntimePropertiesTest {
    @Test
    void piAndClaudeCodeKeepTheirOwnSettings() {
        var values = new HashMap<String, Object>();
        String prefix = "factory.dev-factory.runtime.coding.";
        values.put(prefix + "runtime", "claude-code");
        values.put(prefix + "pi.image", "pi:image");
        values.put(prefix + "pi.provider", "pi-provider");
        values.put(prefix + "pi.model", "new-pi-model");
        values.put(prefix + "pi.api-key", "new-pi-key");
        values.put(prefix + "pi.base-url", "http://pi-gateway.example/v1");
        values.put(prefix + "pi.max-output-tokens", "8192");
        values.put(prefix + "claude-code.image", "claude:image");
        values.put(prefix + "claude-code.max-turns", "120");
        var coding = bind(values).coding();
        assertThat(coding.pi().image()).isEqualTo("pi:image");
        assertThat(coding.pi().provider()).isEqualTo("pi-provider");
        assertThat(coding.pi().model()).isEqualTo("new-pi-model");
        assertThat(coding.pi().apiKey()).isEqualTo("new-pi-key");
        assertThat(coding.pi().maxOutputTokens()).isEqualTo(8192);
        assertThat(coding.claudeCode().image()).isEqualTo("claude:image");
        coding.claudeCode().requireConfigured();
        assertThat(coding.toString() + coding.pi()).doesNotContain("new-pi-key");
        var selected = codingRuntime(new DevFactoryConfiguration(), bind(values));
        assertThat(selected).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(selected.image()).isEqualTo("claude:image");
        assertThat(selected.identity(null)).containsEntry("maxTurns", 120);
    }

    @Test
    void inactiveProfilesAreNotValidatedAndSelectionIsClosed() {
        var configuration = new DevFactoryConfiguration();
        var empty = bind(Map.of("factory.dev-factory.runtime.coding.runtime", "claude-code",
                "factory.dev-factory.runtime.coding.pi.max-output-tokens", "-1"));
        assertThat(codingRuntime(configuration, empty)).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(codingRuntime(configuration, new DevRuntimeProperties(null, null, 0, null, null)))
                .isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThatThrownBy(() -> codingRuntime(configuration, bind(Map.of(
                "factory.dev-factory.runtime.coding.runtime", "unknown"))))
                .hasMessageContaining("Unsupported coding runtime");
    }

    @Test
    void claudeRejectsAModelWithAnotherApiBeforeExecution() {
        var gemini = new ModelEntry("gemini", "openai-completions", "http://gateway/v1", "api-key", "GEMINI_KEY",
                "gemini-3.8-flash", List.of("low", "high"), "high", null, null);
        var catalog = new ModelCatalog(new ModelCatalog.Properties(List.of(gemini), "gemini"), "claude-code",
                Map.of("GEMINI_KEY", "secret")::get);
        var runtime = new ClaudeCodeCodingRuntime(new DevRuntimeProperties.ClaudeCode(null, null), catalog);
        var workload = org.mockito.Mockito.mock(DockerWorkloads.Workload.class);

        assertThatThrownBy(() -> runtime.code(workload, ClaudeCodeCodingRuntimeTest.request(), 30, event -> {},
                CodingTranscripts.NONE))
                .hasMessage("Model gemini uses api openai-completions, but Claude Code understands only"
                        + " anthropic-messages.");
        org.mockito.Mockito.verifyNoInteractions(workload);
    }

    @Test
    void theTurnLimitIsASafetyGateFromOneTo500() {
        assertThat(new DevRuntimeProperties.ClaudeCode(null, null).maxTurns()).isEqualTo(200);
        new DevRuntimeProperties.ClaudeCode(null, 500).requireConfigured();
        assertThatThrownBy(() -> new DevRuntimeProperties.ClaudeCode(null, 501).requireConfigured())
                .hasMessage("FACTORY_CLAUDE_CODE_MAX_TURNS is 501, but it must be from 1 to 500");
        // The selected Claude Code runtime checks it at startup, so the message reaches the log, not a run.
        var bound = bind(Map.of("factory.dev-factory.runtime.coding.claude-code.max-turns", "0"));
        assertThatThrownBy(() -> codingRuntime(new DevFactoryConfiguration(), bound))
                .hasMessage("FACTORY_CLAUDE_CODE_MAX_TURNS is 0, but it must be from 1 to 500");
    }

    private static CodingRuntime codingRuntime(DevFactoryConfiguration configuration, DevRuntimeProperties runtime) {
        return configuration.devCodingRuntime(runtime, new ModelCatalog(null, runtime.coding().runtime(), name -> null));
    }

    private static DevRuntimeProperties bind(Map<String, ?> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
    }

    @Test
    void bindsCodingSettingsFromSpringConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.timeout-seconds", "60",
                "factory.dev-factory.runtime.coding.pi.image", "coding:image",
                "factory.dev-factory.runtime.coding.pi.provider", "codemie",
                "factory.dev-factory.runtime.coding.pi.model", "gemini-3.8-flash",
                "factory.dev-factory.runtime.coding.pi.base-url", "http://host.docker.internal:4001/v1",
                "factory.dev-factory.runtime.coding.pi.api", "openai-completions",
                "factory.dev-factory.runtime.coding.pi.api-key", "test-key",
                "factory.dev-factory.runtime.coding.runtime", "OTHER",
                "factory.dev-factory.runtime.coding.pi.reasoning-effort", "high",
                "factory.dev-factory.runtime.coding.pi.max-output-tokens", "65536"));

        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        var pi = properties.coding().pi();
        assertThat(pi.image()).isEqualTo("coding:image");
        assertThat(pi.provider()).isEqualTo("codemie");
        assertThat(pi.model()).isEqualTo("gemini-3.8-flash");
        assertThat(pi.baseUrl()).isEqualTo("http://host.docker.internal:4001/v1");
        assertThat(pi.api()).isEqualTo("openai-completions");
        assertThat(pi.apiKey()).isEqualTo("test-key");
        assertThat(properties.coding().runtime()).isEqualTo("other");
        assertThat(pi.reasoningEffort()).isEqualTo("high");
        assertThat(pi.maxOutputTokens()).isEqualTo(65536);
        pi.requireConfigured();
    }

    @Test
    void explicitPiProfileRetainsItsSettingsAndFillsDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.coding.runtime", "pi",
                "factory.dev-factory.runtime.coding.pi.provider", "openai-compatible",
                "factory.dev-factory.runtime.coding.pi.model", "test-model",
                "factory.dev-factory.runtime.coding.pi.base-url", "https://api.example.com/v1",
                "factory.dev-factory.runtime.coding.pi.api", "",
                "factory.dev-factory.runtime.coding.pi.reasoning-effort", "",
                "factory.dev-factory.runtime.coding.pi.api-key", "test-key"));
        var coding = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().coding();
        assertThat(coding.pi().provider()).isEqualTo("openai-compatible");
        assertThat(coding.pi().model()).isEqualTo("test-model");
        assertThat(coding.pi().image()).isEqualTo("factory-dev-pi:0.85.1-java21");
        assertThat(coding.pi().api()).isEqualTo("openai-completions");
        assertThat(coding.pi().authentication()).isEqualTo("api-key");
        assertThat(coding.pi().reasoningEffort()).isNull();
        assertThat(coding.pi().maxOutputTokens()).isEqualTo(16384);
        assertThat(coding.runtime()).isEqualTo("pi");
        coding.pi().requireConfigured();
    }

    @Test
    void mavenCacheHasStableDefaultAndAcceptsOperatorOverride() {
        var defaults = new DevRuntimeProperties(null, null, 0, null, null);
        assertThat(defaults.mavenCacheVolume()).isEqualTo("factory-dev-m2-cache");
        assertThat(defaults.coding().runtime()).isEqualTo("claude-code");
        assertThat(defaults.coding().claudeCode().maxTurns()).isEqualTo(200);
        assertThat(defaults.timeoutSeconds()).isEqualTo(1800);
        assertThat(defaults.coding().pi().reasoningEffort()).isNull();
        assertThat(defaults.coding().pi().maxOutputTokens()).isEqualTo(16384);
        assertThat(new DevRuntimeProperties(null, null, 0, "operator-cache", null).mavenCacheVolume())
                .isEqualTo("operator-cache");
    }

    @Test
    void codingRuntimeSelectionIsNormalizedForAdapterLookup() {
        var coding = new DevRuntimeProperties.Coding(" Pi ", null, null);
        assertThat(coding.runtime()).isEqualTo("pi");
    }

    @Test
    void rejectsNamesThatCannotBeDockerVolumeNames() {
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, "bad/name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsMavenReportFamiliesWithoutDependingOnTestClassNames() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.required-reports.roles[0]", "surefire",
                "factory.dev-factory.runtime.required-reports.roles[1]", "failsafe"));

        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();

        assertThat(properties.requiredReports("roles")).containsExactly("surefire", "failsafe");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "**/TEST-*.xml", "../surefire", "target/failsafe-reports/../TEST-a.xml",
            "target/failsafe-reports/TEST-org.folio.roles.it.CacheConfigIT.xml"})
    void rejectsUnknownFamiliesAndUnsafeReportRequirements(String requirement) {
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, null,
                Map.of("roles", List.of(requirement))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
