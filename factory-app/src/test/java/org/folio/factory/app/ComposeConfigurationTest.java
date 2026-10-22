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
            "SOURCE_REPO", "DELIVERY_REPO", "BASE_BRANCH", "DELIVERY_AUTHORIZED");
    private static final Set<String> COMPOSE_ONLY = Set.of(
            "COMPOSE_PROJECT_NAME", "FACTORY_APP_IMAGE", "FACTORY_APP_PORT", "FACTORY_DB_PORT",
            "FACTORY_BIND_ADDRESS", "FACTORY_DEV_FACTORY_REPOSITORIES_FILE");
    private static final Path ROOT = repositoryRoot();

    @TempDir Path fixture;

    @Test
    void everyDocumentedApplicationSettingAndSpringPlaceholderHasAComposeEntry() throws IOException {
        var documented = names(Files.readString(ROOT.resolve(".env.example")),
                "(?m)^\\s*(?:#\\s*)?([A-Z][A-Z0-9_]+)=");
        for (String path : new String[]{"factory-app/src/main/resources/application.yaml",
                "config/developer-repositories.yaml"}) {
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
        var config = render(Map.ofEntries(
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
                Map.entry("FACTORY_SIDECAR_DELIVERY_AUTHORIZED", "false")),
                Map.of("FACTORY_LLM_MODEL", "model-from-shell"));
        var environment = config.path("services").path("factory").path("environment");
        assertThat(environment.path("FACTORY_LLM_MODEL").asString()).isEqualTo("model-from-shell");
        assertThat(environment.path("FACTORY_LLM_PROVIDER").asString()).isEqualTo("openai");
        assertThat(environment.path("FACTORY_LLM_API_KEY").asString()).isEqualTo("fixture-test-provider-key");
        assertThat(environment.path("FACTORY_LLM_BASE_URL").asString()).isEqualTo("https://fixture.invalid/v1");
        assertThat(environment.path("FACTORY_TEST_FACTORY_TARGET_REPO").asString()).isEqualTo("fixture/test-target");
        assertThat(environment.path("FACTORY_TEST_FACTORY_EXECUTION_BASE_URL").asString())
                .isEqualTo("http://fixture.invalid:8000");
        assertThat(environment.path("FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID").asString()).isEqualTo("42");
        assertThat(environment.path("FACTORY_WEBHOOKS_SHARED_SECRET").asString()).isEqualTo("fixture-webhook-secret");
        assertThat(environment.path("FACTORY_LIMITS_MAX_CONCURRENT_EXECUTIONS").asString()).isEqualTo("1");
        assertThat(environment.path("FACTORY_ENGINE_WORKER_THREADS").asString()).isEqualTo("2");
        assertThat(environment.path("FACTORY_DB_POOL_MAX").asString()).isEqualTo("12");
        assertThat(environment.path("FACTORY_HTTP_READ_TIMEOUT").asString()).isEqualTo("17s");
        assertThat(environment.path("FACTORY_RETENTION_RUN_CRON").asString()).isEqualTo("0 15 4 * * *");
        assertThat(environment.path("FACTORY_LOG_FORMAT").asString()).isEqualTo("ecs");
        assertThat(environment.path("FACTORY_CLAUDE_CODE_MODEL").asString()).isEqualTo("coding-model-from-dotenv");
        assertThat(environment.path("FACTORY_SIDECAR_DELIVERY_AUTHORIZED").asString()).isEqualTo("false");
        // Compose's service connection is intentional; host-run DB_URL must not break it.
        assertThat(environment.path("FACTORY_DB_URL").asString()).isEqualTo("jdbc:postgresql://postgres:5432/factory");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-code", "pi"})
    void selectedRuntimeAndRepositoryMountAreResolvedWithoutLoadingDeveloperEnvironment(String runtime)
            throws Exception {
        Path catalog = fixture.resolve("operator-repositories.yaml");
        Files.writeString(catalog, "factory: {}\n");
        var config = render(Map.of("FACTORY_DEV_FACTORY_CODING_RUNTIME", runtime,
                "FACTORY_DEV_FACTORY_REPOSITORIES_FILE", catalog.toString()), Map.of());
        var factory = config.path("services").path("factory");
        var environment = factory.path("environment");
        assertThat(environment.path("FACTORY_DEV_FACTORY_CODING_RUNTIME").asString()).isEqualTo(runtime);
        assertThat(config.path("services").path("coding-runtime").path("image").asString())
                .startsWith(runtime.equals("pi") ? "factory-dev-pi:" : "factory-dev-claude-code:");
        // Unset optional values must remain absent, rather than overriding Spring defaults with empty strings.
        assertThat(environment.path("FACTORY_LLM_MODEL").isNull()
                || environment.path("FACTORY_LLM_MODEL").isMissingNode()).isTrue();
        for (String prefix : REPOSITORY_PREFIXES) {
            for (String field : REPOSITORY_FIELDS) {
                var value = environment.path("FACTORY_" + prefix + "_" + field);
                assertThat(value.isNull() || value.isMissingNode())
                        .as("Compose must retain the catalog default for %s %s", prefix, field).isTrue();
            }
        }
        assertThat(environment.path("SPRING_CONFIG_IMPORT").asString())
                .contains("file:/application/config/developer-repositories.yaml");
        boolean mounted = false;
        for (var volume : factory.path("volumes")) {
            if (volume.path("target").asString("").equals("/application/config/developer-repositories.yaml")) {
                mounted = true;
                assertThat(volume.path("source").asString()).isEqualTo(catalog.toString());
                assertThat(volume.path("read_only").asBoolean()).isTrue();
            }
        }
        assertThat(mounted).as("operator repository catalog must be mounted read-only").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIDECAR", "MOD_ROLES_KEYCLOAK", "MGR_TENANT_ENTITLEMENTS", "MGR_APPLICATIONS"})
    void explicitRepositoryOverridesReachTheAppWithoutInventingDefaultsForOtherTargets(String prefix)
            throws Exception {
        String key = "FACTORY_" + prefix + "_";
        var overrides = Map.of(key + "SOURCE_REPO", "source/custom-module", key + "DELIVERY_REPO", "fork/custom-module",
                key + "BASE_BRANCH", "campaign/custom-base", key + "DELIVERY_AUTHORIZED", "true");
        var environment = render(overrides, Map.of()).path("services").path("factory").path("environment");
        overrides.forEach((name, value) -> assertThat(environment.path(name).asString()).isEqualTo(value));
        for (String other : REPOSITORY_PREFIXES) {
            if (other.equals(prefix)) continue;
            for (String field : REPOSITORY_FIELDS) {
                var value = environment.path("FACTORY_" + other + "_" + field);
                assertThat(value.isNull() || value.isMissingNode())
                        .as("unset override %s %s must not shadow the catalog", other, field).isTrue();
            }
        }
    }

    private JsonNode render(Map<String, String> dotenv, Map<String, String> shell) throws Exception {
        assumeTrue(composeAvailable(), "Docker Compose CLI is required for configuration rendering only");
        // Copy only public Compose files into the fixture: includes cannot consult the real checkout's .env.
        for (String file : new String[]{"docker-compose.yml", "compose.runtime-pi.yml", "compose.runtime-claude-code.yml"})
            Files.copy(ROOT.resolve(file), fixture.resolve(file));
        Files.writeString(fixture.resolve(".env"), "FACTORY_LLM_MODEL=must-not-be-autoloaded\n");
        Path variables = fixture.resolve("operator.env");
        var text = new StringBuilder();
        dotenv.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(variables, text);
        var builder = command("docker", "compose", "--env-file", variables.toString(), "-f",
                fixture.resolve("docker-compose.yml").toString(), "config", "--format", "json");
        builder.environment().putAll(shell);
        Path output = fixture.resolve("rendered.json");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Docker Compose configuration rendering timed out");
        }
        assertThat(process.exitValue()).as("Compose should render the isolated fixture successfully").isZero();
        return JsonMapper.builder().build().readTree(Files.readString(output));
    }

    private static boolean composeAvailable() throws Exception {
        try {
            var process = command("docker", "compose", "version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(10, TimeUnit.SECONDS)) return process.exitValue() == 0;
            process.destroyForcibly();
            return false;
        } catch (IOException absent) {
            return false;
        }
    }

    private static ProcessBuilder command(String... command) {
        var builder = new ProcessBuilder(command);
        var environment = builder.environment();
        environment.keySet().removeIf(key -> !Set.of("PATH", "HOME", "TMPDIR", "SystemRoot").contains(key));
        environment.put("COMPOSE_PROJECT_NAME", "factory-compose-contract");
        return builder;
    }

    private static Set<String> names(String text, String regex) {
        var result = new HashSet<String>();
        var matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("docker-compose.yml"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot locate the Factory Compose configuration");
        return path;
    }
}
