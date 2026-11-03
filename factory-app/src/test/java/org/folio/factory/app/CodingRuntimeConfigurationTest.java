package org.folio.factory.app;

import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** The shipped application.yaml and config/developer-models.yaml, bound the way the application binds them. */
class CodingRuntimeConfigurationTest {
    @Test
    void explicitPiSelectionUsesPiSettingsWithoutClaudeCredentials() throws Exception {
        var setup = setup(Map.of(
                "FACTORY_DEV_FACTORY_CODING_RUNTIME", "pi",
                "FACTORY_PI_PROVIDER", "codemie",
                "FACTORY_PI_MODEL", "pi-model",
                "FACTORY_PI_API_KEY", "pi-key",
                "FACTORY_PI_MAX_OUTPUT_TOKENS", "8192"));
        var coding = setup.properties().coding();

        assertThat(setup.runtime()).isInstanceOf(PiCodingRuntime.class);
        coding.pi().requireConfigured();
        assertThat(coding.pi().model()).isEqualTo("pi-model");
        assertThat(coding.pi().image()).isEqualTo("factory-dev-pi:0.85.1-java21");
        assertThat(coding.pi().api()).isEqualTo("openai-completions");
        assertThat(coding.pi().maxOutputTokens()).isEqualTo(8192);
        assertThat(setup.models().ready()).isEmpty();
    }

    @Test
    void removedModelVariablesNoLongerConfigurePi() throws Exception {
        var coding = setup(Map.of(
                "FACTORY_DEV_FACTORY_CODING_RUNTIME", "pi",
                "FACTORY_MODEL_PROVIDER", "legacy-provider",
                "FACTORY_MODEL_ID", "legacy-model",
                "FACTORY_MODEL_API_KEY", "legacy-key",
                "FACTORY_DEV_FACTORY_CODING_IMAGE", "legacy:image")).properties().coding();

        assertThat(coding.pi().model()).isBlank();
        assertThat(coding.pi().apiKey()).isBlank();
        assertThat(coding.pi().image()).isEqualTo("factory-dev-pi:0.85.1-java21");
        assertThatThrownBy(() -> coding.pi().requireConfigured())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("factory.dev-factory.runtime.coding.pi");
    }

    @Test
    void theLocalCodeMieSonnetIsTheDefaultModelAndItsPublicTokenNeedsNoVariable() throws Exception {
        var setup = setup(Map.of());

        assertThat(setup.properties().coding().runtime()).isEqualTo("claude-code");
        assertThat(setup.runtime()).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(setup.runtime().identity(null)).containsEntry("modelId", "codemie-sonnet-5.5")
                .containsEntry("model", "claude-sonnet-5-5").containsEntry("effort", "high")
                .containsEntry("maxTurns", 200);
        assertThat(setup.properties().timeoutSeconds()).isEqualTo(1800);
        setup.runtime().requireConfigured(null);
        assertThat(setup.properties().coding().pi().apiKey()).isBlank();
    }

    @Test
    void theDefaultModelComesFromEnvAndNeedsOnlyItsOwnSecret() throws Exception {
        var setup = setup(Map.of(
                "FACTORY_DEV_FACTORY_DEFAULT_MODEL", "glm-5.3",
                "FACTORY_ZAI_API_KEY", "token",
                "FACTORY_CODEMIE_PROXY_TOKEN", "",
                "FACTORY_CLAUDE_CODE_MAX_TURNS", "120"));

        setup.runtime().requireConfigured(null);
        assertThat(setup.runtime().identity(null)).containsEntry("modelId", "glm-5.3")
                .containsEntry("model", "glm-5.3").containsEntry("maxTurns", 120);
        assertThat(setup.models().ready()).extracting(model -> model.id()).containsExactly("glm-5.3",
                "glm-5.3-flash");
        assertThat(setup.properties().coding().pi().apiKey()).isBlank();
    }

    @Test
    void aDefaultModelWithoutItsSecretSaysWhichVariableToSet() throws Exception {
        var setup = setup(Map.of("FACTORY_CODEMIE_PROXY_TOKEN", ""));

        assertThatThrownBy(() -> setup.runtime().requireConfigured(null))
                .isInstanceOf(ModelCatalog.ModelException.class)
                .hasMessage("Model codemie-sonnet-5.5 is not configured: set FACTORY_CODEMIE_PROXY_TOKEN in .env."
                        + " If docker-compose.yml does not pass FACTORY_CODEMIE_PROXY_TOKEN to the factory service"
                        + " yet, add it there. Then run docker compose up -d again.");
    }

    private record Setup(DevRuntimeProperties properties, ModelCatalog models, CodingRuntime runtime) {
    }

    private static Setup setup(Map<String, Object> variables) throws Exception {
        var environment = new StandardEnvironment();
        // Never consult the developer's real credentials or runtime selection in these tests.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", variables));
        for (var file : new String[] {"application.yaml", "developer-models.yaml"}) {
            new YamlPropertySourceLoader().load(file, new ClassPathResource(file))
                    .forEach(environment.getPropertySources()::addLast);
        }
        var binder = Binder.get(environment);
        var properties = binder.bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        var catalog = new ModelCatalog(binder.bind("factory.dev-factory", ModelCatalog.Properties.class).get(),
                properties.coding().runtime(), environment::getProperty);
        return new Setup(properties, catalog, new DevFactoryConfiguration().devCodingRuntime(properties, catalog));
    }
}
