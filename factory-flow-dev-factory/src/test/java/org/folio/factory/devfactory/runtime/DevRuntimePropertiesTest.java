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
    void independentProfilesKeepTheirOwnSettingsAndExactClaudeModel() {
        var values = new HashMap<String, Object>();
        String prefix = "factory.dev-factory.runtime.coding.";
        values.put(prefix + "runtime", "claude-code");
        values.put(prefix + "pi.image", "pi:image");
        values.put(prefix + "pi.provider", "pi-provider");
        values.put(prefix + "pi.model", "new-pi-model");
        values.put(prefix + "pi.api-key", "new-pi-key");
        values.put(prefix + "pi.base-url", "http://pi-gateway.example/v1");
        values.put(prefix + "pi.max-output-tokens", "8192");
        values.put(prefix + "claude-code.model", "operator/Custom-Model-2026");
        values.put(prefix + "claude-code.base-url", "http://gateway.example/anthropic");
        values.put(prefix + "claude-code.authentication", "bearer-token");
        values.put(prefix + "claude-code.auth-token", "claude-secret");
        var coding = bind(values).coding();
        assertThat(coding.pi().image()).isEqualTo("pi:image");
        assertThat(coding.pi().provider()).isEqualTo("pi-provider");
        assertThat(coding.pi().model()).isEqualTo("new-pi-model");
        assertThat(coding.pi().apiKey()).isEqualTo("new-pi-key");
        assertThat(coding.pi().maxOutputTokens()).isEqualTo(8192);
        assertThat(coding.claudeCode().model()).isEqualTo("operator/Custom-Model-2026");
        coding.claudeCode().requireConfigured();
        assertThat(coding.toString() + coding.pi() + coding.claudeCode())
                .doesNotContain("new-pi-key", "claude-secret", "gateway.example");
        var selected = codingRuntime(new DevFactoryConfiguration(), bind(values));
        assertThat(selected).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(selected.identity(null)).containsEntry("model", "operator/Custom-Model-2026");
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
    void claudeRejectsWrongProtocolBeforeExecution() {
        var config = new DevRuntimeProperties.ClaudeCode(null, "openai-completions", "http://gateway",
                "model", "api-key", "secret", null, null, null, null);
        var workload = org.mockito.Mockito.mock(DockerWorkloads.Workload.class);
        assertThatThrownBy(() -> ClaudeCodeCodingRuntimeTest.runtime(config).code(workload, ClaudeCodeCodingRuntimeTest.request(), 30, event -> {}, CodingTranscripts.NONE))
                .hasMessageContaining("api=anthropic-messages");
        org.mockito.Mockito.verifyNoInteractions(workload);
    }

    @Test
    void claudeRejectsUnsupportedAuthentication() {
        var profile = new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway", "model",
                "oauth", null, null, null, null, null);

        assertThatThrownBy(profile::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication must be api-key or bearer-token");
    }

    @Test
    void claudeApiKeyAuthenticationRequiresItsOwnCredential() {
        var profile = new DevRuntimeProperties.ClaudeCode(null, null, null, null,
                "api-key", null, null, null, null, null);

        assertThat(profile.authToken()).isEqualTo("codemie-proxy");
        assertThatThrownBy(profile::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selected authentication credential");
    }

    private static CodingRuntime codingRuntime(DevFactoryConfiguration configuration, DevRuntimeProperties runtime) {
        return configuration.devCodingRuntime(runtime, RunProfiles.envOnly(runtime.coding().claudeCode()));
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
        assertThat(defaults.coding().claudeCode().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(defaults.coding().claudeCode().baseUrl()).isEqualTo("http://host.docker.internal:4001");
        assertThat(defaults.coding().claudeCode().authentication()).isEqualTo("bearer-token");
        assertThat(defaults.coding().claudeCode().authToken()).isEqualTo("codemie-proxy");
        assertThat(defaults.coding().claudeCode().maxTurns()).isEqualTo(40);
        assertThat(defaults.coding().claudeCode().effort()).isNull();
        assertThat(defaults.timeoutSeconds()).isEqualTo(1800);
        assertThat(defaults.coding().pi().reasoningEffort()).isNull();
        assertThat(defaults.coding().pi().maxOutputTokens()).isEqualTo(16384);
        assertThat(new DevRuntimeProperties(null, null, 0, "operator-cache", null).mavenCacheVolume())
                .isEqualTo("operator-cache");
    }

    @Test
    void localCodeMieDefaultsUseThePublicBearerTokenInsteadOfAnApiKey() {
        var profile = new DevRuntimeProperties.ClaudeCode(null, null, null, null, null,
                "api-key-is-not-a-token", null, null, null, null);

        assertThat(profile.baseUrl()).isEqualTo("http://host.docker.internal:4001");
        assertThat(profile.authentication()).isEqualTo("bearer-token");
        assertThat(profile.secret()).isEqualTo("codemie-proxy");
        profile.requireConfigured();
    }

    @Test
    void explicitClaudeBearerTokenOverridesTheLocalCodeMieDefault() {
        var configured = new DevRuntimeProperties.ClaudeCode(null, null, null, null, null,
                null, "token", null, null, null);
        configured.requireConfigured();
        assertThat(configured.secret()).isEqualTo("token");
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
