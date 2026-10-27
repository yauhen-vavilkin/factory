package org.folio.factory.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Configuration-only Compose checks: no Docker daemon, containers or real credentials. */
class ComposeConfigurationTest {
    private static final Set<String> REPOSITORY_PREFIXES = Set.of(
            "SIDECAR", "MOD_ROLES_KEYCLOAK", "MGR_TENANT_ENTITLEMENTS", "MGR_APPLICATIONS");
    private static final Set<String> REPOSITORY_FIELDS = Set.of(
            "SOURCE_REPO", "DELIVERY_REPO", "BASE_BRANCH");
    private static final Set<String> COMPOSE_ONLY = Set.of(
            "COMPOSE_PROJECT_NAME", "FACTORY_APP_IMAGE", "FACTORY_APP_PORT", "FACTORY_DB_PORT",
            "FACTORY_BIND_ADDRESS", "FACTORY_DEV_FACTORY_REPOSITORIES_FILE", "FACTORY_DEV_FACTORY_RUN_PROFILES_FILE");
    private static final Path ROOT = repositoryRoot();
    private static final String CATALOG_MOUNT = "/application/config/developer-repositories.yaml";
    private static final String PROFILES_MOUNT = "/application/config/developer-run-profiles.yaml";
    private static final Set<String> INHERITED_PROCESS_VARIABLES =
            Set.of("PATH", "HOME", "TMPDIR", "SystemRoot");

    @TempDir Path fixture;

    @Test
    void everyDocumentedApplicationSettingAndSpringPlaceholderHasAComposeEntry() throws IOException {
        var documented = names(Files.readString(ROOT.resolve(".env.example")),
                "(?m)^\\s*(?:#\\s*)?([A-Z][A-Z0-9_]+)=");
        documented.addAll(names(Files.readString(ROOT.resolve(".env.pilot.example")),
                "(?m)^\\s*(?:#\\s*)?([A-Z][A-Z0-9_]+)="));
        for (String path : new String[]{"factory-app/src/main/resources/application.yaml",
                "config/developer-repositories.yaml", "config/developer-run-profiles.yaml"}) {
            documented.addAll(names(Files.readString(ROOT.resolve(path)), "\\$\\{([A-Z][A-Z0-9_]+)[:}]"));
        }
        documented.removeAll(COMPOSE_ONLY);
        var forwarded = names(Files.readString(ROOT.resolve("docker-compose.yml")),
                "(?m)^      ([A-Z][A-Z0-9_]+):");
        assertThat(forwarded).as("Compose must forward every documented application setting")
                .containsAll(documented);
    }

    @Test
    void dotenvReachesBothFlowsAndOperationalSettingsWhileShellWins() throws Exception {
        Map<String, String> dotenv = Map.ofEntries(
                Map.entry("FACTORY_LLM_PROVIDER", "openai"),
                Map.entry("FACTORY_LLM_API_KEY", "fixture-test-provider-key"),
                Map.entry("FACTORY_LLM_MODEL", "model-from-dotenv"),
                Map.entry("FACTORY_LLM_BASE_URL", "https://fixture.invalid/v1"),
                Map.entry("FACTORY_TEST_FACTORY_TARGET_REPO", "fixture/test-target"),
                Map.entry("FACTORY_TEST_FACTORY_EXECUTION_BASE_URL", "http://fixture.invalid:8000"),
                Map.entry("FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID", "42"),
                Map.entry("FACTORY_WEBHOOKS_SHARED_SECRET", "fixture-webhook-secret"),
                Map.entry("FACTORY_LIMITS_MAX_CONCURRENT_EXECUTIONS", "1"),
                Map.entry("FACTORY_ENGINE_WORKER_THREADS", "2"),
                Map.entry("FACTORY_DB_POOL_MAX", "12"),
                Map.entry("FACTORY_HTTP_READ_TIMEOUT", "17s"),
                Map.entry("FACTORY_RETENTION_RUN_CRON", "0 15 4 * * *"),
                Map.entry("FACTORY_LOG_FORMAT", "ecs"),
                Map.entry("FACTORY_CLAUDE_CODE_MODEL", "coding-model-from-dotenv"),
                Map.entry("FACTORY_CLAUDE_CODE_BASE_URL", "https://fixture.invalid/anthropic"),
                Map.entry("FACTORY_CLAUDE_CODE_AUTHENTICATION", "api-key"),
                Map.entry("FACTORY_CLAUDE_CODE_API_KEY", "fixture-coding-key"),
                Map.entry("FACTORY_CLAUDE_CODE_AUTH_TOKEN", "fixture-custom-proxy-token"),
                Map.entry("FACTORY_SIDECAR_DELIVERY_REPO", "fork/folio-module-sidecar"));
        Map<String, String> shell = Map.of("FACTORY_LLM_MODEL", "model-from-shell");
        var config = render(dotenv, shell);
        var environment = config.path("services").path("factory").path("environment");

        Map<String, String> expected = new HashMap<>(dotenv);
        expected.putAll(shell);
        assertEnvironmentContains(environment, expected);
        // Compose's service connection is intentional; host-run DB_URL must not break it.
        assertThat(environment.path("FACTORY_DB_URL").asString()).isEqualTo("jdbc:postgresql://postgres:5432/factory");
    }

    @Test
    void defaultsSelectLocalCodeMieWithSonnetAndBearerAuthentication() throws Exception {
        var config = render(Map.of(), Map.of());
        var environment = config.path("services").path("factory").path("environment");

        assertLocalCodeMieProfile(environment);
        assertThat(environment.path("FACTORY_CLAUDE_CODE_AUTH_TOKEN").asString()).isEqualTo("codemie-proxy");
        assertThat(config.path("services").path("coding-runtime").path("image").asString())
                .startsWith("factory-dev-claude-code:");
        // Unset values arrive empty, which Factory treats as unset: env-default and "not configured".
        assertThat(environment.path("FACTORY_ZAI_API_KEY").asString()).isEmpty();
        assertThat(environment.path("FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE").asString()).isEmpty();
    }

    @Test
    void copiedExampleUsesLocalProxyAndDoesNotOverrideTheCatalog() throws Exception {
        Map<String, String> example = exampleEnvironment(".env.example");
        var config = render(example, Map.of());
        var environment = config.path("services").path("factory").path("environment");

        assertLocalCodeMieProfile(environment);
        assertThat(environment.path("FACTORY_CLAUDE_CODE_AUTH_TOKEN").asString())
                .isEqualTo("codemie-proxy");
        for (String prefix : REPOSITORY_PREFIXES) {
            for (String field : REPOSITORY_FIELDS) {
                assertThat(isUnset(environment.path("FACTORY_" + prefix + "_" + field)))
                        .as("The example must keep the catalog %s for %s", field, prefix)
                        .isTrue();
            }
        }
    }

    @Test
    void pilotExampleAsksOnlyForTheAssigneeAndTokenAndStaysOnTheSupportedLocalProfile() throws Exception {
        Map<String, String> pilot = exampleEnvironment(".env.pilot.example");
        var config = render(pilot, Map.of());
        var environment = config.path("services").path("factory").path("environment");

        assertThat(pilot).containsOnlyKeys("FACTORY_CONNECTORS_JIRA_BASE_URL", "FACTORY_PILOT_ASSIGNEE",
                "FACTORY_CONNECTORS_GITHUB_TOKEN", "FACTORY_PILOT_EXECUTION_ENABLED");
        assertLocalCodeMieProfile(environment);
        assertEnvironmentContains(environment, Map.of(
                "FACTORY_CONNECTORS_JIRA_BASE_URL", "https://folio-org.atlassian.net",
                "FACTORY_PILOT_EXECUTION_ENABLED", "true"));
        // Retention keeps its off default; pilot execution refuses to start tasks while it is on.
        assertThat(isUnset(environment.path("FACTORY_RETENTION_ENABLED"))).isTrue();
        assertThat(isUnset(environment.path("FACTORY_PILOT_EXECUTION_DISPATCH_INTERVAL_MS"))).isTrue();
    }

    @Test
    void publishedPortsStayLoopbackOnlyAndTheCodingRuntimeExposesNothing() throws Exception {
        var config = render(Map.of(), Map.of());
        var factory = config.path("services").path("factory");
        var coding = config.path("services").path("coding-runtime");

        assertThat(coding.path("ports").isMissingNode()
                || coding.path("ports").isEmpty()).isTrue();
        for (JsonNode port : factory.path("ports")) {
            assertThat(port.path("host_ip").asString()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void catalogAndPricingMountsAreReadOnlyAndPersistentStorageUsesNamedVolumes() throws Exception {
        var services = render(Map.of(), Map.of()).path("services");
        var factory = services.path("factory");

        JsonNode catalog = requireVolume(factory, CATALOG_MOUNT);
        assertThat(catalog.path("read_only").asBoolean()).isTrue();
        JsonNode pricing = requireVolume(factory, "/application/config/developer-pricing.yaml");
        assertThat(pricing.path("read_only").asBoolean()).isTrue();
        JsonNode profiles = requireVolume(factory, PROFILES_MOUNT);
        assertThat(profiles.path("read_only").asBoolean()).isTrue();
        assertThat(profiles.path("source").asString()).endsWith("config/developer-run-profiles.yaml");
        assertThat(requireVolume(services.path("postgres"), "/var/lib/postgresql/data")
                .path("source").asString()).endsWith("factory-pgdata");
        JsonNode m2 = requireVolume(factory, "/var/lib/factory-dev-m2-cache");
        assertThat(m2.path("read_only").asBoolean()).isTrue();
    }

    @Test
    void runProfilesFileSecretAndDefaultProfileReachTheApp() throws Exception {
        Path profiles = fixture.resolve("operator-run-profiles.yaml");
        Files.writeString(profiles, "factory: {}\n");
        var factory = render(Map.of("FACTORY_DEV_FACTORY_RUN_PROFILES_FILE", profiles.toString(),
                "FACTORY_ZAI_API_KEY", "fixture-zai-key", "FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE", "glm-5.3"),
                Map.of()).path("services").path("factory");
        var environment = factory.path("environment");

        assertEnvironmentContains(environment, Map.of("FACTORY_ZAI_API_KEY", "fixture-zai-key",
                "FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE", "glm-5.3"));
        assertThat(environment.path("SPRING_CONFIG_IMPORT").asString())
                .contains("classpath:developer-run-profiles.yaml,file:" + PROFILES_MOUNT);
        assertThat(requireVolume(factory, PROFILES_MOUNT).path("source").asString()).isEqualTo(profiles.toString());
    }

    @Test
    void theCodingRuntimeCommandStaysThePinnedVersionGateNotInferenceEvidence() throws Exception {
        var coding = render(Map.of(), Map.of()).path("services").path("coding-runtime");

        var command = new StringBuilder();
        coding.path("command").forEach(part -> command.append(part.asString()).append(' '));
        assertThat(command.toString()).contains("claude --version").contains("2.1.287 (Claude Code)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-code", "pi"})
    void selectedRuntimeAndRepositoryMountAreResolvedWithoutLoadingDeveloperEnvironment(String runtime)
            throws Exception {
        Path catalog = fixture.resolve("operator-repositories.yaml");
        Files.writeString(catalog, "factory: {}\n");
        var config = render(Map.of(
                "FACTORY_DEV_FACTORY_CODING_RUNTIME", runtime,
                "FACTORY_DEV_FACTORY_REPOSITORIES_FILE", catalog.toString()), Map.of());
        var factory = config.path("services").path("factory");
        var environment = factory.path("environment");
        assertThat(environment.path("FACTORY_DEV_FACTORY_CODING_RUNTIME").asString()).isEqualTo(runtime);
        assertThat(config.path("services").path("coding-runtime").path("image").asString())
                .startsWith(runtime.equals("pi") ? "factory-dev-pi:" : "factory-dev-claude-code:");
        // Unset optional values must remain absent, rather than overriding Spring defaults with empty strings.
        assertThat(isUnset(environment.path("FACTORY_LLM_MODEL"))).isTrue();
        for (String prefix : REPOSITORY_PREFIXES) {
            for (String field : REPOSITORY_FIELDS) {
                var value = environment.path("FACTORY_" + prefix + "_" + field);
                assertThat(isUnset(value))
                        .as("Compose must retain the catalog default for %s %s", prefix, field).isTrue();
            }
        }
        assertThat(environment.path("SPRING_CONFIG_IMPORT").asString())
                .contains("file:/application/config/developer-repositories.yaml");
        JsonNode catalogVolume = requireVolume(factory, CATALOG_MOUNT);
        assertThat(catalogVolume.path("source").asString()).isEqualTo(catalog.toString());
        assertThat(catalogVolume.path("read_only").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIDECAR", "MOD_ROLES_KEYCLOAK", "MGR_TENANT_ENTITLEMENTS", "MGR_APPLICATIONS"})
    void explicitRepositoryOverridesReachTheAppWithoutInventingDefaultsForOtherTargets(String prefix)
            throws Exception {
        String key = "FACTORY_" + prefix + "_";
        var overrides = Map.of(
                key + "SOURCE_REPO", "source/custom-module",
                key + "DELIVERY_REPO", "fork/custom-module",
                key + "BASE_BRANCH", "campaign/custom-base");
        var environment = render(overrides, Map.of()).path("services").path("factory").path("environment");
        assertEnvironmentContains(environment, overrides);
        for (String other : REPOSITORY_PREFIXES) {
            if (other.equals(prefix)) {
                continue;
            }
            for (String field : REPOSITORY_FIELDS) {
                var value = environment.path("FACTORY_" + other + "_" + field);
                assertThat(isUnset(value))
                        .as("unset override %s %s must not shadow the catalog", other, field).isTrue();
            }
        }
    }

    private static void assertLocalCodeMieProfile(JsonNode environment) {
        assertEnvironmentContains(environment, Map.of(
                "FACTORY_DEV_FACTORY_CODING_RUNTIME", "claude-code",
                "FACTORY_CLAUDE_CODE_API", "anthropic-messages",
                "FACTORY_CLAUDE_CODE_BASE_URL", "http://host.docker.internal:4001",
                "FACTORY_CLAUDE_CODE_MODEL", "claude-sonnet-5-5",
                "FACTORY_CLAUDE_CODE_AUTHENTICATION", "bearer-token",
                "FACTORY_CLAUDE_CODE_MAX_TURNS", "40",
                "FACTORY_DEV_FACTORY_TIMEOUT_SECONDS", "1800"));
        assertThat(environment.path("FACTORY_CLAUDE_CODE_EFFORT").asString()).isEmpty();
    }

    private static Map<String, String> exampleEnvironment(String fileName) throws IOException {
        Map<String, String> variables = new HashMap<>();
        for (String line : Files.readAllLines(ROOT.resolve(fileName))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] assignment = line.split("=", 2);
            assertThat(assignment).as("Environment assignment: %s", line).hasSize(2);
            variables.put(assignment[0], assignment[1]);
        }
        return variables;
    }

    private JsonNode render(Map<String, String> dotenv, Map<String, String> shell) throws Exception {
        assumeTrue(composeAvailable(), "Docker Compose CLI is required for configuration rendering only");
        Path variables = prepareFixture(dotenv);
        var builder = command(
                "docker", "compose", "--env-file", variables.toString(), "-f",
                fixture.resolve("docker-compose.yml").toString(), "config", "--format", "json");
        builder.environment().putAll(shell);
        Path output = fixture.resolve("rendered.json");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Docker Compose configuration rendering timed out");
        }
        assertThat(process.exitValue())
                .as("Compose should render the isolated fixture successfully")
                .isZero();
        return JsonMapper.builder().build().readTree(Files.readString(output));
    }

    private Path prepareFixture(Map<String, String> dotenv) throws IOException {
        // Copy only public Compose files into the fixture: includes cannot consult the real checkout's .env.
        String[] composeFiles = {
                "docker-compose.yml", "compose.runtime-pi.yml", "compose.runtime-claude-code.yml"
        };
        for (String file : composeFiles) {
            Files.copy(ROOT.resolve(file), fixture.resolve(file));
        }
        Files.writeString(fixture.resolve(".env"), "FACTORY_LLM_MODEL=must-not-be-autoloaded\n");
        Path variables = fixture.resolve("operator.env");
        var text = new StringBuilder();
        dotenv.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(variables, text);
        return variables;
    }

    private static boolean composeAvailable() throws Exception {
        try {
            var process = command("docker", "compose", "version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                return process.exitValue() == 0;
            }
            process.destroyForcibly();
            return false;
        } catch (IOException absent) {
            return false;
        }
    }

    private static ProcessBuilder command(String... command) {
        var builder = new ProcessBuilder(command);
        var environment = builder.environment();
        environment.keySet().removeIf(key -> !INHERITED_PROCESS_VARIABLES.contains(key));
        environment.put("COMPOSE_PROJECT_NAME", "factory-compose-contract");
        return builder;
    }

    private static Set<String> names(String text, String regex) {
        var result = new HashSet<String>();
        var matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("docker-compose.yml"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("Cannot locate the Factory Compose configuration");
        }
        return path;
    }

    private static void assertEnvironmentContains(JsonNode environment, Map<String, String> expected) {
        for (var entry : expected.entrySet()) {
            assertThat(environment.path(entry.getKey()).asString())
                    .as("Compose environment variable %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    private static boolean isUnset(JsonNode value) {
        return value.isNull() || value.isMissingNode();
    }

    private static JsonNode requireVolume(JsonNode service, String target) {
        for (JsonNode volume : service.path("volumes")) {
            if (target.equals(volume.path("target").asString(""))) {
                return volume;
            }
        }
        throw new AssertionError("Missing Compose volume mounted at " + target);
    }
}
