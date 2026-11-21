package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {

    private static final Map<String, String> SECRETS = Map.of("PROXY_TOKEN", "proxy-secret",
            "EXAMPLE_KEY", "example-secret", "PI_KEY", "pi-secret");

    /**
     * Shaped like config/developer-models.yaml, but owned by this test so that a new model or price in the
     * committed file does not break it: two proxy models share one secret, two entries share one model string,
     * one entry's secret is unset and one entry uses an API that Claude Code cannot run.
     */
    private static final ModelCatalog.Properties FIXTURE = file(
            anthropic("proxy-sonnet", "bearer-token", "PROXY_TOKEN", "claude-sonnet", "high",
                    price("2.00", "10.00")),
            anthropic("proxy-opus", "bearer-token", "PROXY_TOKEN", "claude-opus", "medium", price("4.00", "20.00")),
            anthropic("direct-sonnet", "api-key", "ANTHROPIC_KEY", "claude-sonnet", "high", price("2.00", "10.00")),
            new ModelEntry("example-large", "anthropic-messages", "https://api.example.com/anthropic", "bearer-token",
                    "EXAMPLE_KEY", "example-large", List.of("low", "high", "max"), "max", null, price("1.40", "4.40"),
                    null),
            new ModelEntry("example-small", "anthropic-messages", "https://api.example.com/anthropic", "bearer-token",
                    "EXAMPLE_KEY", "example-small", List.of("low", "high", "max"), "max", null, null, null),
            new ModelEntry("gemini", "openai-completions", "http://host.docker.internal:4001/v1", "api-key", "PI_KEY",
                    "gemini-flash", List.of("low", "medium", "high"), "medium", null, price("0.75", "3.75"), null));

    @Test
    void theCommittedFileBindsAndEveryEntryIsValid() throws Exception {
        var file = committedFile();

        // The constructor checks every entry and the default model, and throws on the first mistake.
        var catalog = new ModelCatalog(file, "claude-code", name -> null);

        assertThat(file.models()).isNotEmpty();
        assertThat(catalog.all()).hasSameSizeAs(file.models());
        assertThat(catalog.defaultModel()).isPresent();
    }

    @Test
    void imagesTrueInTheModelsFileReachesTheRuntimeDecision() throws Exception {
        String entry = """
                      - id: %s
                        api: anthropic-messages
                        base-url: http://host.docker.internal:4001
                        authentication: bearer-token
                        secret-env: PROXY_TOKEN
                        model: claude-sonnet
                        efforts: [high]
                        default-effort: high
                """;
        var file = bind(new ByteArrayResource(("factory:\n  dev-factory:\n    models:\n" + entry.formatted("reads")
                + "        images: true\n" + entry.formatted("plain")).getBytes(StandardCharsets.UTF_8)));
        var catalog = new ModelCatalog(file, "claude-code", SECRETS::get);
        var runtime = new ClaudeCodeCodingRuntime(new DevRuntimeProperties.ClaudeCode(null, 10), catalog);

        assertThat(catalog.find("reads").orElseThrow().images()).isTrue();
        assertThat(catalog.find("plain").orElseThrow().images()).isFalse();
        assertThat(runtime.acceptsImages(new CodingRequest.RunSettings("reads", null, null))).isTrue();
        assertThat(runtime.acceptsImages(new CodingRequest.RunSettings("plain", null, null))).isFalse();
    }

    @Test
    void everySecretOfTheCommittedFileIsRedacted() throws Exception {
        var names = committedFile().models().stream().map(ModelEntry::secretEnv).distinct().toList();
        var values = new java.util.LinkedHashMap<String, String>();
        names.forEach(name -> values.put(name, "value-" + values.size() + "-of-" + name));

        var catalog = new ModelCatalog(committedFile(), "claude-code", values::get);

        assertThat(names).isNotEmpty();
        String all = String.join(" ", values.values());
        assertThat(catalog.redact(all)).isEqualTo(String.join(" ", java.util.Collections.nCopies(names.size(),
                "[REDACTED]")));
        assertThat(catalog.all().toString()).doesNotContain(values.values().toArray(String[]::new));
    }

    @Test
    void theCatalogOffersTheClaudeCodeModelsWhoseSecretIsSet() {
        var catalog = new ModelCatalog(FIXTURE, "claude-code", SECRETS::get);

        assertThat(catalog.all()).extracting(Model::id).containsExactly("proxy-sonnet", "proxy-opus",
                "direct-sonnet", "example-large", "example-small", "gemini");
        assertThat(catalog.ready()).extracting(Model::id).containsExactly("proxy-sonnet", "proxy-opus",
                "example-large", "example-small");
        assertThat(catalog.defaultModel().orElseThrow().id()).isEqualTo("proxy-sonnet");
        var example = catalog.find("example-large").orElseThrow();
        assertThat(example.baseUrl()).isEqualTo("https://api.example.com/anthropic");
        assertThat(example.endpointOrigin()).isEqualTo("https://api.example.com");
        assertThat(example.secretVariable()).isEqualTo("EXAMPLE_KEY");
        assertThat(example.efforts()).containsExactly("low", "high", "max");
        assertThat(example.defaultEffort()).isEqualTo("max");
        assertThat(example.maxOutputTokens()).isEqualTo(16384);
        assertThat(catalog.all().toString()).doesNotContain("proxy-secret", "example-secret", "pi-secret");
        // Every secret is hidden, also those of models that Claude Code cannot run.
        assertThat(catalog.redact("proxy-secret example-secret pi-secret"))
                .isEqualTo("[REDACTED] [REDACTED] [REDACTED]");
    }

    @Test
    void onlyASecretOfSixteenOrMoreCharactersCountsInAPatch() {
        var secrets = Map.of("PROXY_TOKEN", "codemie-proxy", "EXAMPLE_KEY", "example-0123456789abcdef",
                "PI_KEY", "pi-secret");
        var catalog = new ModelCatalog(FIXTURE, "claude-code", secrets::get);

        assertThat(catalog.holdsSecret("token: example-0123456789abcdef")).isTrue();
        // The public word of a local proxy and other short secrets also turn up in ordinary code.
        assertThat(catalog.holdsSecret("Start the proxy with codemie-proxy, then pi-secret.")).isFalse();
    }

    @Test
    void aModelThatCannotRunSaysWhatToFix() {
        var catalog = new ModelCatalog(FIXTURE, "claude-code", SECRETS::get);

        assertThat(catalog.problem(catalog.find("direct-sonnet").orElseThrow())).contains("Model"
                + " direct-sonnet is not configured: set ANTHROPIC_KEY in .env. If docker-compose.yml"
                + " does not pass ANTHROPIC_KEY to the factory service yet, add it there. Then run docker"
                + " compose up -d again.");
        assertThat(catalog.problem(catalog.find("gemini").orElseThrow())).contains("Model"
                + " gemini uses api openai-completions, but Claude Code understands only anthropic-messages.");
        var odd = new ModelCatalog(file(entry("odd", List.of("low", "minimal"), "low")), "claude-code", SECRETS::get);
        assertThat(odd.problem(odd.find("odd").orElseThrow())).contains("Model odd lists effort minimal, but Claude"
                + " Code accepts only low, medium, high, xhigh, max.");
        assertThatThrownBy(() -> catalog.choose("direct-sonnet", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("set ANTHROPIC_KEY");
    }

    @Test
    void withPiNoModelIsOfferedAndThereIsNoDefault() {
        var catalog = new ModelCatalog(FIXTURE, "pi", SECRETS::get);

        assertThat(catalog.ready()).isEmpty();
        assertThat(catalog.defaultModel()).isEmpty();
        assertThat(catalog.problem(catalog.find("example-large").orElseThrow()))
                .contains("Pi takes its model from FACTORY_PI_MODEL; this file gives Pi only prices.");
    }

    @Test
    void theRunFormIsCheckedAgainstTheChosenModel() {
        var catalog = new ModelCatalog(FIXTURE, "claude-code", SECRETS::get);

        var defaults = catalog.choose("", "", "  ");
        assertThat(defaults.model().id()).isEqualTo("proxy-sonnet");
        assertThat(defaults.effort()).isEqualTo("high");
        assertThat(defaults.instructions()).isNull();
        var example = catalog.choose("example-large", "low", " Use the REST URL. ");
        assertThat(example.model().id()).isEqualTo("example-large");
        assertThat(example.effort()).isEqualTo("low");
        assertThat(example.instructions()).isEqualTo("Use the REST URL.");
        assertThatThrownBy(() -> catalog.choose("example-large", "medium", null))
                .hasMessage("Effort medium is not available for model example-large. Choose one of: low, high, max.");
        assertThatThrownBy(() -> catalog.choose("other", null, null))
                .hasMessage("There is no model other. Reload the page and choose a model from the list.");
        assertThatThrownBy(() -> catalog.choose(null, null, "x".repeat(8001)))
                .hasMessageContaining("at most 8000 characters; this text has 8001");
        assertThat(catalog.choose(null, null, "x".repeat(8000)).instructions()).hasSize(8000);
    }

    @Test
    void theDefaultModelIsTheNamedOneOrTheFirstThatClaudeCodeCanRun() {
        var models = FIXTURE.models();
        var named = new ModelCatalog(new ModelCatalog.Properties(models, "example-large"), "claude-code",
                name -> null);
        assertThat(named.defaultModel().orElseThrow().id()).isEqualTo("example-large");
        // Readiness does not move the default: without secrets it stays the first Claude Code model.
        var blank = new ModelCatalog(new ModelCatalog.Properties(models, " "), "claude-code", name -> null);
        assertThat(blank.defaultModel().orElseThrow().id()).isEqualTo("proxy-sonnet");
        assertThatThrownBy(() -> new ModelCatalog(new ModelCatalog.Properties(models, "example-huge"), "claude-code",
                name -> null))
                .hasMessage("FACTORY_DEV_FACTORY_DEFAULT_MODEL is example-huge, but config/developer-models.yaml has"
                        + " no model with that id. Leave it empty or use an id from the file.");
        var none = new ModelCatalog(file(), "claude-code", name -> null);
        assertThatThrownBy(() -> none.choose(null, null, null))
                .hasMessage("No model in config/developer-models.yaml can run with Claude Code. Add a model with api"
                        + " anthropic-messages; the Configuration tab shows what each model needs.");
    }

    @Test
    void invalidEntriesStopStartupWithAMessageThatNamesTheField() {
        assertInvalid(entry("example-large", List.of("low", "high"), "max"), "config/developer-models.yaml,"
                + " model example-large: default-effort must be one of its efforts: low, high.");
        assertInvalid(entry("example-large", List.of(), "max"),
                "efforts must list the model's effort levels once each");
        assertInvalid(entry("example-large", List.of("low", "low"), "low"),
                "efforts must list the model's effort levels");
        assertInvalid(entry("Example 5", List.of("max"), "max"), "every model needs an id of lower-case letters");
        assertInvalid(new ModelEntry("example-large", "openai-chat", "https://api.example.com", "bearer-token",
                "FACTORY_EXAMPLE_API_KEY", "example-large", List.of("max"), "max", null, null, null),
                "api must be one of: openai-completions");
        assertInvalid(new ModelEntry("example-large", "anthropic-messages", "https://user:pw@api.example.com",
                "bearer-token", "FACTORY_EXAMPLE_API_KEY", "example-large", List.of("max"), "max", null, null, null),
                "base-url must be an HTTP(S) URL");
        assertInvalid(new ModelEntry("example-large", "anthropic-messages", "https://api.example.com", "bearer-token",
                "example-secret-value", "example-large", List.of("max"), "max", null, null, null),
                "secret-env must be the name");
        assertInvalid(new ModelEntry("example-large", "anthropic-messages", "https://api.example.com", "oauth",
                "FACTORY_EXAMPLE_API_KEY", "example-large", List.of("max"), "max", null, null, null),
                "authentication must be");
        assertInvalid(withPrice(new ModelPrice("usd", LocalDate.now(), null, null, null, null, null, null, null)),
                "price.currency must be an ISO code");
        assertInvalid(withPrice(new ModelPrice("USD", LocalDate.now(), java.net.URI.create("http://x.example"),
                null, null, null, null, null, null)), "price.source must be the https address");
        assertInvalid(withPrice(new ModelPrice("USD", LocalDate.now(), java.net.URI.create("https://x.example"),
                BigDecimal.ONE.negate(), null, null, null, null, null)), "a price cannot be negative");
        var twice = entry("example-large", List.of("max"), "max");
        assertThatThrownBy(() -> new ModelCatalog(file(twice, twice), "claude-code", name -> null))
                .hasMessage("config/developer-models.yaml: model id example-large is used twice; give each model its"
                        + " own id.");
    }

    @Test
    void runsResolveTheirModelOrFailWithAReasonThatNamesIt() {
        var catalog = new ModelCatalog(FIXTURE, "claude-code", SECRETS::get);

        assertThat(catalog.resolve(null).model().id()).isEqualTo("proxy-sonnet");
        // Runs from before the models file name the old env-default profile and may store no effort.
        var legacy = catalog.resolve(new CodingRequest.RunSettings("env-default", null, null));
        assertThat(legacy.model().id()).isEqualTo("proxy-sonnet");
        assertThat(legacy.effort()).isEqualTo("high");
        var run = catalog.resolve(new CodingRequest.RunSettings("example-small", null, "Note"));
        assertThat(run.effort()).isEqualTo("max");
        assertThat(run.instructions()).isEqualTo("Note");
        assertThatThrownBy(() -> catalog.resolve(new CodingRequest.RunSettings("removed", null, null)))
                .isInstanceOf(ModelCatalog.ModelException.class)
                .hasMessage("This run uses model removed, which is not in config/developer-models.yaml any more."
                        + " Add it back, or retry the task with another model.");
        assertThatThrownBy(() -> catalog.resolve(new CodingRequest.RunSettings("example-large", "medium", null)))
                .isInstanceOf(ModelCatalog.ModelException.class).hasMessageContaining("no longer allows effort");
    }

    @Test
    void storedSettingsKeepTheirJsonNamesAndOldTurnLimitsAreSkipped() {
        var json = JsonMapper.builder().build();

        var old = json.readValue(
                "{\"profileId\":\"example-large\",\"effort\":\"max\",\"maxTurns\":1,\"instructions\":null}",
                CodingRequest.RunSettings.class);
        assertThat(old).isEqualTo(new CodingRequest.RunSettings("example-large", "max", null));
        assertThat(json.writeValueAsString(new CodingRequest.RunSettings("example-large", "low", null)))
                .contains("\"profileId\":\"example-large\"").doesNotContain("modelId", "maxTurns");
    }

    @Test
    void thePriceIsFoundByEntryOrByAModelStringThatOnlyOneEntryHas() {
        var catalog = new ModelCatalog(FIXTURE, "claude-code", SECRETS::get);

        assertThat(catalog.pricedEntry("proxy-opus", "anything").orElseThrow().price().outputPerMillion())
                .isEqualByComparingTo("20.00");
        assertThat(catalog.pricedEntry(null, "example-large").orElseThrow().id()).isEqualTo("example-large");
        assertThat(catalog.pricedEntry(null, "gemini-flash").orElseThrow().id()).isEqualTo("gemini");
        // An entry without a price is not priced, by id or by its model string.
        assertThat(catalog.pricedEntry("example-small", "example-small")).isEmpty();
        assertThat(catalog.pricedEntry(null, "example-small")).isEmpty();
        // Two entries run claude-sonnet at different providers: without the entry id the price is unknown.
        assertThat(catalog.pricedEntry(null, "claude-sonnet")).isEmpty();
        assertThat(catalog.pricedEntry("removed", "claude-sonnet")).isEmpty();
        // A removed entry is not priced as another entry that happens to have the same model string.
        assertThat(catalog.pricedEntry("removed", "example-large")).isEmpty();
    }

    private static void assertInvalid(ModelEntry entry, String message) {
        assertThatThrownBy(() -> new ModelCatalog(file(entry), "claude-code", name -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static ModelEntry entry(String id, List<String> efforts, String defaultEffort) {
        return new ModelEntry(id, "anthropic-messages", "https://api.example.com/anthropic", "bearer-token",
                "FACTORY_EXAMPLE_API_KEY", "example-large", efforts, defaultEffort, null, null, null);
    }

    private static ModelEntry withPrice(ModelPrice price) {
        return new ModelEntry("example-large", "anthropic-messages", "https://api.example.com", "bearer-token",
                "FACTORY_EXAMPLE_API_KEY", "example-large", List.of("max"), "max", null, price, null);
    }

    private static ModelEntry anthropic(String id, String authentication, String secretEnv, String model,
                                        String defaultEffort, ModelPrice price) {
        return new ModelEntry(id, "anthropic-messages", "http://host.docker.internal:4001", authentication, secretEnv,
                model, List.of("low", "medium", "high", "xhigh", "max"), defaultEffort, null, price, null);
    }

    private static ModelPrice price(String input, String output) {
        return new ModelPrice("USD", LocalDate.of(2026, 11, 3), java.net.URI.create("https://prices.example.org"),
                new BigDecimal(input), null, null, null, null, new BigDecimal(output));
    }

    private static ModelCatalog.Properties file(ModelEntry... entries) {
        return new ModelCatalog.Properties(List.of(entries), null);
    }

    private static ModelCatalog.Properties committedFile() throws Exception {
        Path file = Path.of("..", "config", "developer-models.yaml");
        if (!Files.exists(file)) {
            file = Path.of("config", "developer-models.yaml");
        }
        return bind(new FileSystemResource(file));
    }

    private static ModelCatalog.Properties bind(Resource yaml) throws Exception {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("models", yaml).forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("factory.dev-factory", ModelCatalog.Properties.class).get();
    }
}
