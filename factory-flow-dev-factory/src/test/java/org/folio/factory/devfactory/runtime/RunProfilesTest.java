package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunProfilesTest {

    private static final DevRuntimeProperties.ClaudeCode ENV = new DevRuntimeProperties.ClaudeCode(null, null,
            "http://gateway.example:4001/anthropic", "claude-sonnet-5-5", "bearer-token", "legacy-key",
            "legacy-token", 40, 16384, null);

    @Test
    void theCommittedFileShipsBothGlmProfilesAndAnOldEnvKeepsWorking() throws Exception {
        var profiles = new RunProfiles(ENV, committedFile(), Map.of("FACTORY_ZAI_API_KEY", "zai-secret")::get);

        assertThat(profiles.all()).extracting(RunProfiles.Profile::id)
                .containsExactly("env-default", "glm-5.3", "glm-5.3-flash");
        assertThat(profiles.defaultProfile().id()).isEqualTo("env-default");
        var envDefault = profiles.defaultProfile();
        assertThat(envDefault.label()).isEqualTo("Configured in .env");
        assertThat(envDefault.efforts()).containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(envDefault.defaultEffort()).isNull();
        assertThat(envDefault.endpointOrigin()).isEqualTo("http://gateway.example:4001");
        var glm = profiles.find("glm-5.3").orElseThrow();
        assertThat(glm.baseUrl()).isEqualTo("https://api.z.ai/api/anthropic");
        assertThat(glm.authentication()).isEqualTo("bearer-token");
        assertThat(glm.secretVariable()).isEqualTo("FACTORY_ZAI_API_KEY");
        assertThat(glm.efforts()).containsExactly("low", "high", "max");
        assertThat(glm.defaultEffort()).isEqualTo("max");
        assertThat(glm.maxTurns()).isEqualTo(100);
        assertThat(glm.maxOutputTokens()).isEqualTo(16384);
        assertThat(glm.configured()).isTrue();
        assertThat(profiles.find("glm-5.3-flash").orElseThrow().model()).isEqualTo("glm-5.3-flash");
        assertThat(profiles.all().toString()).doesNotContain("zai-secret", "legacy-token");
        assertThat(profiles.redact("zai-secret legacy-key legacy-token"))
                .isEqualTo("[REDACTED] [REDACTED] [REDACTED]");
    }

    @Test
    void aProfileWithAnEmptySecretIsNotConfiguredAndCannotBeChosen() throws Exception {
        var profiles = new RunProfiles(ENV, committedFile(), name -> "");

        assertThat(profiles.find("glm-5.3").orElseThrow().configured()).isFalse();
        assertThatThrownBy(() -> profiles.choose("glm-5.3", "max", "100", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Profile glm-5.3 is not configured: set FACTORY_ZAI_API_KEY in .env. If docker-compose.yml"
                        + " does not pass FACTORY_ZAI_API_KEY to the factory service yet, add it there. Then run"
                        + " docker compose up -d again.");
    }

    @Test
    void envDefaultWithAnEmptySecretNamesOnlyEnvBecauseComposeAlreadyPassesItsVariables() {
        var env = new DevRuntimeProperties.ClaudeCode(null, null, null, null, "api-key", null, null, 40, 16384, null);

        assertThat(RunProfiles.envOnly(env).defaultProfile().problem())
                .isEqualTo("Profile env-default is not configured: set FACTORY_CLAUDE_CODE_API_KEY in .env, then run"
                        + " docker compose up -d again.");
    }

    @Test
    void envMaxTurnsAbove500StopsEnvDefaultWithAReasonThatPointsToEnv() throws Exception {
        var env = new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway.example:4001", "claude-sonnet-5-5",
                "bearer-token", null, "legacy-token", 600, 16384, null);
        var profiles = new RunProfiles(env, committedFile(), name -> "zai-secret");
        var runtime = new ClaudeCodeCodingRuntime(env, profiles);

        assertThat(profiles.defaultProfile().configured()).isFalse();
        assertThatThrownBy(() -> runtime.requireConfigured(null))
                .isInstanceOf(RunProfiles.ProfileException.class)
                .hasMessage("Profile env-default cannot run: FACTORY_CLAUDE_CODE_MAX_TURNS is 600, but it must be"
                        + " from 1 to 500. Fix the FACTORY_CLAUDE_CODE_* settings in .env, then run docker compose"
                        + " up -d again.");
        // The broken default does not block a run with another profile.
        runtime.requireConfigured(new CodingRequest.RunSettings("glm-5.3", null, null, null));
        var limit = new DevRuntimeProperties.ClaudeCode(null, null, null, null, null, null, null, 500, 16384, null);
        assertThat(RunProfiles.envOnly(limit).defaultProfile().configured()).isTrue();
        // A value chosen in a run form and stored on the attempt is still checked against the limit.
        assertThatThrownBy(() -> profiles.resolve(new CodingRequest.RunSettings("glm-5.3", null, 501, null)))
                .isInstanceOf(RunProfiles.ProfileException.class)
                .hasMessageContaining("Max turns for profile glm-5.3 must be from 1 to 500");
    }

    @Test
    void theRunFormIsCheckedAgainstTheChosenProfile() throws Exception {
        var profiles = new RunProfiles(ENV, committedFile(), name -> "zai-secret");

        var defaults = profiles.choose("", "", "", "  ");
        assertThat(defaults.profile().id()).isEqualTo("env-default");
        assertThat(defaults.effort()).isNull();
        assertThat(defaults.maxTurns()).isEqualTo(40);
        assertThat(defaults.instructions()).isNull();
        var glm = profiles.choose("glm-5.3", "low", "250", " Use the REST URL. ");
        assertThat(glm.profile().id()).isEqualTo("glm-5.3");
        assertThat(glm.effort()).isEqualTo("low");
        assertThat(glm.maxTurns()).isEqualTo(250);
        assertThat(glm.instructions()).isEqualTo("Use the REST URL.");
        assertThat(profiles.choose("env-default", "xhigh", "1", null).effort()).isEqualTo("xhigh");
        assertThatThrownBy(() -> profiles.choose("glm-5.3", "medium", "10", null))
                .hasMessage("Effort medium is not available for profile glm-5.3. Choose one of: low, high, max.");
        assertThatThrownBy(() -> profiles.choose("glm-5.3", "max", "501", null))
                .hasMessage("Max turns must be a whole number from 1 to 500.");
        assertThatThrownBy(() -> profiles.choose("glm-5.3", "max", "ten", null))
                .hasMessage("Max turns must be a whole number from 1 to 500.");
        assertThatThrownBy(() -> profiles.choose("other", null, null, null))
                .hasMessageContaining("There is no run profile other");
        assertThatThrownBy(() -> profiles.choose(null, null, null, "x".repeat(8001)))
                .hasMessageContaining("at most 8000 characters; this text has 8001");
        assertThat(profiles.choose(null, null, null, "x".repeat(8000)).instructions()).hasSize(8000);
    }

    @Test
    void aBlankDefaultProfileMeansEnvDefaultAndAnUnknownOneStopsStartup() throws Exception {
        var file = committedFile();
        assertThat(new RunProfiles(ENV, new RunProfiles.Properties(file.runProfiles(), " "), name -> null)
                .defaultProfile().id()).isEqualTo("env-default");
        assertThat(new RunProfiles(ENV, new RunProfiles.Properties(file.runProfiles(), "glm-5.3"), name -> null)
                .defaultProfile().id()).isEqualTo("glm-5.3");
        assertThatThrownBy(() -> new RunProfiles(ENV, new RunProfiles.Properties(file.runProfiles(), "glm-6"),
                name -> null))
                .hasMessageContaining("FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE is glm-6");
    }

    @Test
    void invalidProfilesStopStartupWithAMessageThatNamesTheField() {
        assertInvalid(profile("glm", List.of("low", "ultra"), "low"), "efforts must list values from");
        assertInvalid(profile("glm", List.of("low", "high"), "max"), "default-effort must be one of its efforts");
        assertInvalid(profile("env-default", List.of("max"), "max"), "reserved id env-default");
        assertInvalid(new RunProfiles.FileProfile("glm", null, "https://user:pw@api.z.ai", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm", List.of("max"), "max", 10, 10), "base-url must be an HTTP(S) URL");
        assertInvalid(new RunProfiles.FileProfile("glm", null, "https://api.z.ai", "bearer-token",
                "zai-secret-value", "glm", List.of("max"), "max", 10, 10), "secret-env must be the name");
        assertInvalid(new RunProfiles.FileProfile("glm", null, "https://api.z.ai", "oauth",
                "FACTORY_ZAI_API_KEY", "glm", List.of("max"), "max", 10, 10), "authentication must be");
        assertInvalid(new RunProfiles.FileProfile("glm", null, "https://api.z.ai", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm", List.of("max"), "max", 501, 10), "max-turns must be from 1 to 500");
    }

    @Test
    void runsResolveTheirProfileOrFailWithAReasonThatNamesIt() throws Exception {
        var profiles = new RunProfiles(ENV, committedFile(), name -> "zai-secret");

        assertThat(profiles.resolve(null).profile().id()).isEqualTo("env-default");
        var run = profiles.resolve(new CodingRequest.RunSettings("glm-5.3-flash", null, null, "Note"));
        assertThat(run.effort()).isEqualTo("max");
        assertThat(run.maxTurns()).isEqualTo(100);
        assertThat(run.instructions()).isEqualTo("Note");
        assertThatThrownBy(() -> profiles.resolve(new CodingRequest.RunSettings("removed", null, null, null)))
                .isInstanceOf(RunProfiles.ProfileException.class)
                .hasMessage("This run uses profile removed, which is not in config/developer-run-profiles.yaml"
                        + " any more. Add the profile back, or retry the task with another profile.");
        assertThatThrownBy(() -> profiles.resolve(new CodingRequest.RunSettings("glm-5.3", "medium", null, null)))
                .isInstanceOf(RunProfiles.ProfileException.class).hasMessageContaining("no longer allows effort");
    }

    private static void assertInvalid(RunProfiles.FileProfile profile, String message) {
        assertThatThrownBy(() -> new RunProfiles(ENV, new RunProfiles.Properties(List.of(profile), null),
                name -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private static RunProfiles.FileProfile profile(String id, List<String> efforts, String defaultEffort) {
        return new RunProfiles.FileProfile(id, null, "https://api.z.ai/api/anthropic", "bearer-token",
                "FACTORY_ZAI_API_KEY", "glm", efforts, defaultEffort, 10, 10);
    }

    private static RunProfiles.Properties committedFile() throws Exception {
        Path file = Path.of("..", "config", "developer-run-profiles.yaml");
        if (!Files.exists(file)) {
            file = Path.of("config", "developer-run-profiles.yaml");
        }
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("profiles", new FileSystemResource(file))
                .forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("factory.dev-factory", RunProfiles.Properties.class).get();
    }
}
