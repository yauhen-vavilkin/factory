package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CodingRuntimeConfigurationTest {
    @Test void explicitPiSelectionPreservesLegacySettingsWithoutClaudeCredentials() throws Exception {
        var properties = bind(Map.of("FACTORY_DEV_FACTORY_CODING_RUNTIME", "pi",
                "FACTORY_MODEL_PROVIDER", "codemie", "FACTORY_MODEL_ID", "legacy-model",
                "FACTORY_MODEL_API_KEY", "legacy-key", "FACTORY_MODEL_MAX_OUTPUT_TOKENS", "8192"));
        var coding = properties.coding();
        assertThat(new DevFactoryConfiguration().devCodingRuntime(properties)).isInstanceOf(PiCodingRuntime.class);
        coding.piProfile().requireConfigured();
        assertThat(coding.piProfile().model()).isEqualTo("legacy-model");
        assertThat(coding.piProfile().maxOutputTokens()).isEqualTo(8192);
        assertThat(coding.claudeCode().authentication()).isBlank();
    }

    @Test void newPiEnvironmentOverridesLegacySettingsFieldByField() throws Exception {
        var coding = bind(Map.of("FACTORY_DEV_FACTORY_CODING_RUNTIME", "pi",
                "FACTORY_MODEL_PROVIDER", "legacy-provider", "FACTORY_MODEL_ID", "legacy-model",
                "FACTORY_MODEL_API_KEY", "legacy-key", "FACTORY_PI_MODEL", "new-model",
                "FACTORY_PI_API_KEY", "new-key", "FACTORY_PI_IMAGE", "new:image")).coding();
        coding.piProfile().requireConfigured();
        assertThat(coding.piProfile().model()).isEqualTo("new-model");
        assertThat(coding.piProfile().apiKey()).isEqualTo("new-key");
        assertThat(coding.piProfile().image()).isEqualTo("new:image");
        assertThat(coding.piProfile().provider()).isEqualTo("legacy-provider");
        assertThat(coding.piProfile().maxOutputTokens()).isEqualTo(16384);
    }

    @Test void claudeCodeAndSonnetAreDefaultsWithoutPiCredentials() throws Exception {
        var properties = bind(Map.of("FACTORY_CLAUDE_CODE_BASE_URL", "https://gateway/anthropic",
                "FACTORY_CLAUDE_CODE_AUTHENTICATION", "bearer-token", "FACTORY_CLAUDE_CODE_AUTH_TOKEN", "token"));
        var runtime = new DevFactoryConfiguration().devCodingRuntime(properties);
        assertThat(properties.coding().runtime()).isEqualTo("claude-code");
        assertThat(runtime).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(runtime.identity()).containsEntry("model", "claude-sonnet-5-5");
        runtime.requireConfigured();
        assertThat(properties.coding().piProfile().apiKey()).isBlank();
    }

    @Test void claudeEnvironmentSelectsIndependentRuntimeWithoutPiCredentials() throws Exception {
        var properties = bind(Map.of("FACTORY_DEV_FACTORY_CODING_RUNTIME", "claude-code",
                "FACTORY_CLAUDE_CODE_MODEL", "exact/Model", "FACTORY_CLAUDE_CODE_BASE_URL", "https://gateway/anthropic",
                "FACTORY_CLAUDE_CODE_AUTHENTICATION", "bearer-token", "FACTORY_CLAUDE_CODE_AUTH_TOKEN", "token"));
        var runtime = new DevFactoryConfiguration().devCodingRuntime(properties);
        assertThat(runtime).isInstanceOf(ClaudeCodeCodingRuntime.class);
        runtime.requireConfigured();
        assertThat(properties.coding().piProfile().apiKey()).isBlank();
        assertThat(runtime.identity()).containsEntry("model", "exact/Model");
    }

    private static DevRuntimeProperties bind(Map<String, Object> variables) throws Exception {
        var environment = new StandardEnvironment();
        // Never consult the developer's real credentials or runtime selection in these tests.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", variables));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")))
            environment.getPropertySources().addLast(source);
        return Binder.get(environment).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
    }
}
