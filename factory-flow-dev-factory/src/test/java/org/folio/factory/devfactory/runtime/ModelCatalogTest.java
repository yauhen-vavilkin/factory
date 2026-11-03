package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    private static final Map<String, String> SECRETS = Map.of("FACTORY_CODEMIE_PROXY_TOKEN", "proxy-secret",
            "FACTORY_ZAI_API_KEY", "zai-secret", "FACTORY_PI_API_KEY", "pi-secret");

    @Test
    void theCommittedFileOffersTheClaudeCodeModelsWhoseSecretIsSet() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "claude-code", SECRETS::get);

        assertThat(catalog.all()).extracting(Model::id).containsExactly("codemie-sonnet-5.5", "codemie-opus-5.5",
                "codemie-haiku-5.5", "anthropic-sonnet-5.5", "glm-5.3", "glm-5.3-flash", "codemie-gemini-3.8-flash",
                "codemie-gpt-5.6-luna");
        assertThat(catalog.ready()).extracting(Model::id).containsExactly("codemie-sonnet-5.5", "codemie-opus-5.5",
                "codemie-haiku-5.5", "glm-5.3", "glm-5.3-flash");
        assertThat(catalog.defaultModel().orElseThrow().id()).isEqualTo("codemie-sonnet-5.5");
        var glm = catalog.find("glm-5.3").orElseThrow();
        assertThat(glm.baseUrl()).isEqualTo("https://api.z.ai/api/anthropic");
        assertThat(glm.endpointOrigin()).isEqualTo("https://api.z.ai");
        assertThat(glm.secretVariable()).isEqualTo("FACTORY_ZAI_API_KEY");
        assertThat(glm.efforts()).containsExactly("low", "high", "max");
        assertThat(glm.defaultEffort()).isEqualTo("max");
        assertThat(glm.maxOutputTokens()).isEqualTo(16384);
        var sonnet = catalog.find("codemie-sonnet-5.5").orElseThrow().price();
        assertThat(sonnet.asOf()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(sonnet.cacheWrite5mPerMillion()).isEqualByComparingTo("2.50");
        assertThat(sonnet.cacheWritePerMillion()).isNull();
        assertThat(catalog.all().toString()).doesNotContain("proxy-secret", "zai-secret", "pi-secret");
        // Every secret is hidden, also those of models that Claude Code cannot run.
        assertThat(catalog.redact("proxy-secret zai-secret pi-secret")).isEqualTo("[REDACTED] [REDACTED] [REDACTED]");
    }

    @Test
    void aModelThatCannotRunSaysWhatToFix() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "claude-code", SECRETS::get);

        assertThat(catalog.problem(catalog.find("anthropic-sonnet-5.5").orElseThrow())).contains("Model"
                + " anthropic-sonnet-5.5 is not configured: set FACTORY_ANTHROPIC_API_KEY in .env. If docker-compose.yml"
                + " does not pass FACTORY_ANTHROPIC_API_KEY to the factory service yet, add it there. Then run docker"
                + " compose up -d again.");
        assertThat(catalog.problem(catalog.find("codemie-gemini-3.8-flash").orElseThrow())).contains("Model"
                + " codemie-gemini-3.8-flash uses api openai-completions, but Claude Code understands only"
                + " anthropic-messages.");
        var odd = new ModelCatalog(file(entry("odd", List.of("low", "minimal"), "low")), "claude-code", SECRETS::get);
        assertThat(odd.problem(odd.find("odd").orElseThrow())).contains("Model odd lists effort minimal, but Claude"
                + " Code accepts only low, medium, high, xhigh, max.");
        assertThatThrownBy(() -> catalog.choose("anthropic-sonnet-5.5", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("set FACTORY_ANTHROPIC_API_KEY");
    }

    @Test
    void withPiNoModelIsOfferedAndThereIsNoDefault() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "pi", SECRETS::get);

        assertThat(catalog.ready()).isEmpty();
        assertThat(catalog.defaultModel()).isEmpty();
        assertThat(catalog.problem(catalog.find("glm-5.3").orElseThrow()))
                .contains("Pi takes its model from FACTORY_PI_MODEL; this file gives Pi only prices.");
    }

    @Test
    void theRunFormIsCheckedAgainstTheChosenModel() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "claude-code", SECRETS::get);

        var defaults = catalog.choose("", "", "  ");
        assertThat(defaults.model().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(defaults.effort()).isEqualTo("high");
        assertThat(defaults.instructions()).isNull();
        var glm = catalog.choose("glm-5.3", "low", " Use the REST URL. ");
        assertThat(glm.model().id()).isEqualTo("glm-5.3");
        assertThat(glm.effort()).isEqualTo("low");
        assertThat(glm.instructions()).isEqualTo("Use the REST URL.");
        assertThatThrownBy(() -> catalog.choose("glm-5.3", "medium", null))
                .hasMessage("Effort medium is not available for model glm-5.3. Choose one of: low, high, max.");
        assertThatThrownBy(() -> catalog.choose("other", null, null))
                .hasMessage("There is no model other. Reload the page and choose a model from the list.");
        assertThatThrownBy(() -> catalog.choose(null, null, "x".repeat(8001)))
                .hasMessageContaining("at most 8000 characters; this text has 8001");
        assertThat(catalog.choose(null, null, "x".repeat(8000)).instructions()).hasSize(8000);
    }

    @Test
    void theDefaultModelIsTheNamedOneOrTheFirstThatClaudeCodeCanRun() throws Exception {
        var models = committedFile().models();
        var named = new ModelCatalog(new ModelCatalog.Properties(models, "glm-5.3"), "claude-code", name -> null);
        assertThat(named.defaultModel().orElseThrow().id()).isEqualTo("glm-5.3");
        // Readiness does not move the default: without secrets it stays the first Claude Code model.
        var blank = new ModelCatalog(new ModelCatalog.Properties(models, " "), "claude-code", name -> null);
        assertThat(blank.defaultModel().orElseThrow().id()).isEqualTo("codemie-sonnet-5.5");
        assertThatThrownBy(() -> new ModelCatalog(new ModelCatalog.Properties(models, "glm-6"), "claude-code",
                name -> null))
                .hasMessage("FACTORY_DEV_FACTORY_DEFAULT_MODEL is glm-6, but config/developer-models.yaml has no"
                        + " model with that id. Leave it empty or use an id from the file.");
        var none = new ModelCatalog(file(), "claude-code", name -> null);
        assertThatThrownBy(() -> none.choose(null, null, null))
                .hasMessage("No model in config/developer-models.yaml can run with Claude Code. Add a model with api"
                        + " anthropic-messages; Check configuration shows what each model needs.");
    }

    @Test
    void invalidEntriesStopStartupWithAMessageThatNamesTheField() {
        assertInvalid(entry("glm", List.of("low", "high"), "max"),
                "config/developer-models.yaml, model glm: default-effort must be one of its efforts: low, high.");
        assertInvalid(entry("glm", List.of(), "max"), "efforts must list the model's effort levels once each");
        assertInvalid(entry("glm", List.of("low", "low"), "low"), "efforts must list the model's effort levels");
        assertInvalid(entry("Glm 5", List.of("max"), "max"), "every model needs an id of lower-case letters");
        assertInvalid(new ModelEntry("glm", "openai-chat", "https://api.z.ai", "bearer-token", "FACTORY_ZAI_API_KEY",
                "glm", List.of("max"), "max", null, null), "api must be one of: openai-completions");
        assertInvalid(new ModelEntry("glm", "anthropic-messages", "https://user:pw@api.z.ai", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm", List.of("max"), "max", null, null), "base-url must be an HTTP(S) URL");
        assertInvalid(new ModelEntry("glm", "anthropic-messages", "https://api.z.ai", "bearer-token",
                "zai-secret-value", "glm", List.of("max"), "max", null, null), "secret-env must be the name");
        assertInvalid(new ModelEntry("glm", "anthropic-messages", "https://api.z.ai", "oauth",
                "FACTORY_ZAI_API_KEY", "glm", List.of("max"), "max", null, null), "authentication must be");
        assertInvalid(withPrice(new ModelPrice("usd", LocalDate.now(), null, null, null, null, null, null, null)),
                "price.currency must be an ISO code");
        assertInvalid(withPrice(new ModelPrice("USD", LocalDate.now(), java.net.URI.create("http://x.example"),
                null, null, null, null, null, null)), "price.source must be the https address");
        assertInvalid(withPrice(new ModelPrice("USD", LocalDate.now(), java.net.URI.create("https://x.example"),
                BigDecimal.ONE.negate(), null, null, null, null, null)), "a price cannot be negative");
        var twice = entry("glm", List.of("max"), "max");
        assertThatThrownBy(() -> new ModelCatalog(file(twice, twice), "claude-code", name -> null))
                .hasMessage("config/developer-models.yaml: model id glm is used twice; give each model its own id.");
    }

    @Test
    void runsResolveTheirModelOrFailWithAReasonThatNamesIt() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "claude-code", SECRETS::get);

        assertThat(catalog.resolve(null).model().id()).isEqualTo("codemie-sonnet-5.5");
        // Runs from before the models file name the old env-default profile and may store no effort.
        var legacy = catalog.resolve(new CodingRequest.RunSettings("env-default", null, null));
        assertThat(legacy.model().id()).isEqualTo("codemie-sonnet-5.5");
        assertThat(legacy.effort()).isEqualTo("high");
        var run = catalog.resolve(new CodingRequest.RunSettings("glm-5.3-flash", null, "Note"));
        assertThat(run.effort()).isEqualTo("max");
        assertThat(run.instructions()).isEqualTo("Note");
        assertThatThrownBy(() -> catalog.resolve(new CodingRequest.RunSettings("removed", null, null)))
                .isInstanceOf(ModelCatalog.ModelException.class)
                .hasMessage("This run uses model removed, which is not in config/developer-models.yaml any more."
                        + " Add it back, or retry the task with another model.");
        assertThatThrownBy(() -> catalog.resolve(new CodingRequest.RunSettings("glm-5.3", "medium", null)))
                .isInstanceOf(ModelCatalog.ModelException.class).hasMessageContaining("no longer allows effort");
    }

    @Test
    void storedSettingsKeepTheirJsonNamesAndOldTurnLimitsAreSkipped() {
        var json = JsonMapper.builder().build();

        var old = json.readValue("{\"profileId\":\"glm-5.3\",\"effort\":\"max\",\"maxTurns\":1,\"instructions\":null}",
                CodingRequest.RunSettings.class);
        assertThat(old).isEqualTo(new CodingRequest.RunSettings("glm-5.3", "max", null));
        assertThat(json.writeValueAsString(new CodingRequest.RunSettings("glm-5.3", "low", null)))
                .contains("\"profileId\":\"glm-5.3\"").doesNotContain("modelId", "maxTurns");
    }

    @Test
    void thePriceIsFoundByEntryOrByAModelStringThatOnlyOneEntryHas() throws Exception {
        var catalog = new ModelCatalog(committedFile(), "claude-code", SECRETS::get);

        assertThat(catalog.pricedEntry("codemie-opus-5.5", "anything").orElseThrow().price().outputPerMillion())
                .isEqualByComparingTo("20.00");
        assertThat(catalog.pricedEntry(null, "glm-5.3").orElseThrow().id()).isEqualTo("glm-5.3");
        assertThat(catalog.pricedEntry(null, "gemini-3.8-flash").orElseThrow().id())
                .isEqualTo("codemie-gemini-3.8-flash");
        // Two entries run claude-sonnet-5-5 at different providers: without the entry id the price is unknown.
        assertThat(catalog.pricedEntry(null, "claude-sonnet-5-5")).isEmpty();
        assertThat(catalog.pricedEntry("removed", "claude-sonnet-5-5")).isEmpty();
        // A removed entry is not priced as another entry that happens to have the same model string.
        assertThat(catalog.pricedEntry("removed", "glm-5.3")).isEmpty();
        var unpriced = new ModelCatalog(file(entry("glm", List.of("max"), "max")), "claude-code", name -> null);
        assertThat(unpriced.pricedEntry("glm", "glm")).isEmpty();
        assertThat(unpriced.pricedEntry(null, "glm")).isEmpty();
    }

    private static void assertInvalid(ModelEntry entry, String message) {
        assertThatThrownBy(() -> new ModelCatalog(file(entry), "claude-code", name -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static ModelEntry entry(String id, List<String> efforts, String defaultEffort) {
        return new ModelEntry(id, "anthropic-messages", "https://api.z.ai/api/anthropic", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm", efforts, defaultEffort, null, null);
    }

    private static ModelEntry withPrice(ModelPrice price) {
        return new ModelEntry("glm", "anthropic-messages", "https://api.z.ai", "bearer-token", "FACTORY_ZAI_API_KEY",
                "glm", List.of("max"), "max", null, price);
    }

    private static ModelCatalog.Properties file(ModelEntry... entries) {
        return new ModelCatalog.Properties(List.of(entries), null);
    }

    private static ModelCatalog.Properties committedFile() throws Exception {
        Path file = Path.of("..", "config", "developer-models.yaml");
        if (!Files.exists(file)) {
            file = Path.of("config", "developer-models.yaml");
        }
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("models", new FileSystemResource(file))
                .forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("factory.dev-factory", ModelCatalog.Properties.class).get();
    }
}
