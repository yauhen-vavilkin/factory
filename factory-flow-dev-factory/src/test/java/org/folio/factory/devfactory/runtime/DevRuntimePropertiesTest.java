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
    void theSelectedClaudeCodeRuntimeUsesItsOwnSettingsAndThePiKeyStaysOutOfToString() {
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
                "gemini-3.8-flash", List.of("low", "high"), "high", null, null, null);
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
        assertThat(new DevRuntimeProperties.ClaudeCode(null, null).maxTurns()).isEqualTo(400);
        new DevRuntimeProperties.ClaudeCode(null, 500).requireConfigured();
        assertThatThrownBy(() -> new DevRuntimeProperties.ClaudeCode(null, 501).requireConfigured())
                .hasMessage("FACTORY_CLAUDE_CODE_MAX_TURNS is 501, but it must be from 1 to 500");
        // The selected Claude Code runtime checks it at startup, so the message reaches the log, not a run.
        var bound = bind(Map.of("factory.dev-factory.runtime.coding.claude-code.max-turns", "0"));
        assertThatThrownBy(() -> codingRuntime(new DevFactoryConfiguration(), bound))
                .hasMessage("FACTORY_CLAUDE_CODE_MAX_TURNS is 0, but it must be from 1 to 500");
    }

    @Test
    void imagesAreOffUnlessThePiProfileEnablesThem() {
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.runtime", "pi")).coding().pi().images()).isFalse();
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.pi.images", "true")).coding().pi().images()).isTrue();
    }

    @Test
    void attachmentLimitsDefaultToTenImagesAndThreeMegabytes() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.attachments.max-images", "", "factory.dev-factory.attachments.max-bytes", "")));
        var limits = binder.bind("factory.dev-factory.attachments", DevAttachmentProperties.class)
                .orElseGet(DevAttachmentProperties::new);

        assertThat(limits.maxImages()).isEqualTo(10);
        assertThat(limits.maxBytes()).isEqualTo(3L * 1024 * 1024);
        assertThat(attachments("factory.dev-factory.attachments.max-images", "0").maxImages()).isZero();
        assertThat(attachments("factory.dev-factory.attachments.max-images", "5").maxImages()).isEqualTo(5);
    }

    private static DevAttachmentProperties attachments(String key, String value) {
        return new Binder(new MapConfigurationPropertySource(Map.of(key, value)))
                .bind("factory.dev-factory.attachments", DevAttachmentProperties.class).get();
    }

    private static CodingRuntime codingRuntime(DevFactoryConfiguration configuration, DevRuntimeProperties runtime) {
        return DevFactoryConfiguration.selectCodingRuntime(runtime, new ModelCatalog(null, runtime.coding().runtime(), name -> null));
    }

    private static DevRuntimeProperties bind(Map<String, ?> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
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
        assertThat(coding.pi().image()).isEqualTo("factory-dev-pi:0.85.1-java21-node22");
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
        assertThat(defaults.timeoutSeconds()).isEqualTo(3600);
        assertThat(defaults.coding().pi().reasoningEffort()).isNull();
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

    @Test
    void plansDefaultToMavenAndBindAnExplicitToolchain() {
        var plans = Map.of("unit", java.util.List.of("mvn", "test"));
        assertThat(new DevRuntimeProperties(plans, null, 0, null, null).toolchain("unit")).isEqualTo(Toolchain.MAVEN);
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.plans.unit[0]", "mvn",
                "factory.dev-factory.runtime.plans.unit[1]", "test",
                "factory.dev-factory.runtime.plan-toolchains.unit", "maven"));
        var bound = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        assertThat(bound.planToolchains()).containsEntry("unit", Toolchain.MAVEN);
        assertThat(bound.toolchain("unit")).isEqualTo(Toolchain.MAVEN);
    }

    @Test
    void bindsYarnPlansAndRejectsMavenReportPathsForThem() {
        var plans = Map.of("jest", java.util.List.of("yarn", "test"));
        var yarn = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN), null, 0);
        assertThat(yarn.toolchain("jest")).isEqualTo(Toolchain.YARN);
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.plans.jest[0]", "yarn",
                "factory.dev-factory.runtime.plan-toolchains.jest", "yarn"));
        assertThat(new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get()
                .toolchain("jest")).isEqualTo(Toolchain.YARN);
        assertThatThrownBy(() -> new DevRuntimeProperties(plans, null, 0, null,
                Map.of("jest", java.util.List.of("target/surefire-reports/TEST-a.xml")),
                Map.of("jest", Toolchain.YARN), null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jest");
    }

    @Test
    void rejectsToolchainsForUnknownPlansOrUnknownEcosystems() {
        assertThatThrownBy(() -> new DevRuntimeProperties(Map.of("unit", java.util.List.of("mvn", "test")),
                null, 0, null, Map.of(), Map.of("typo", Toolchain.MAVEN), null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plan-toolchains")
                .hasMessageContaining("typo");
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.plans.unit[0]", "mvn",
                "factory.dev-factory.runtime.plan-toolchains.unit", "gradle"));
        assertThatThrownBy(() -> new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get())
                .isInstanceOf(org.springframework.boot.context.properties.bind.BindException.class);
    }

    @Test
    void yarnSettingsHaveDefaultsBindAndAreValidated() {
        var defaults = new DevRuntimeProperties(null, null, 0, null, null);
        assertThat(defaults.yarn().cacheVolume()).isEqualTo("factory-dev-yarn-cache");
        assertThat(defaults.yarn().folioRegistry()).isEqualTo("https://repository.folio.org/repository/npm-folioci/");
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.yarn.cache-volume", "ops-yarn",
                "factory.dev-factory.runtime.yarn.folio-registry", "https://npm.example.org/folio/"));
        var yarn = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().yarn();
        assertThat(yarn.cacheVolume()).isEqualTo("ops-yarn");
        assertThat(yarn.folioRegistry()).isEqualTo("https://npm.example.org/folio/");
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn("bad/name", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn(null, "https://npm.example.org/'; rm -rf /", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn(null, "ftp://npm.example.org/", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void environmentFollowsThePlansToolchain() {
        var plans = Map.of("unit", java.util.List.of("mvn", "test"), "jest", java.util.List.of("yarn", "test"));
        var runtime = new DevRuntimeProperties(plans, null, 0, "operator-m2", Map.of(), Map.of("jest", Toolchain.YARN), null, 0);

        assertThat(runtime.environment("unit")).isEqualTo(BuildEnvironment.maven("operator-m2"));
        assertThat(runtime.environment("jest")).isEqualTo(new BuildEnvironment(Toolchain.YARN,
                "factory-dev-yarn-cache", "https://repository.folio.org/repository/npm-folioci/"));
    }

    @Test
    void seedTimeoutDefaultsBindsAndFallsBackWhenNotPositive() {
        assertThat(new DevRuntimeProperties(Map.of(), null, 0, null, null).seedTimeoutSeconds()).isEqualTo(300);
        assertThat(bind(Map.of("factory.dev-factory.runtime.seed-timeout-seconds", "900")).seedTimeoutSeconds())
                .isEqualTo(900);
        assertThat(bind(Map.of("factory.dev-factory.runtime.seed-timeout-seconds", "0")).seedTimeoutSeconds())
                .isEqualTo(DevRuntimeProperties.DEFAULT_SEED_TIMEOUT_SECONDS);
        assertThat(bind(Map.of("factory.dev-factory.runtime.timeout-seconds", "60")).seedTimeoutSeconds())
                .isEqualTo(300);
    }

    @Test
    void yarnInstallCommandHasADefaultBindsAndIsValidated() {
        assertThat(new DevRuntimeProperties.Yarn(null, null, null).installCommand())
                .containsExactly("yarn", "install", "--frozen-lockfile", "--ignore-scripts", "--non-interactive",
                        "--mutex", "file:/tmp/factory-home/.yarn-cache/.yarn-mutex");
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.yarn.install-command[0]", "yarn",
                "factory.dev-factory.runtime.yarn.install-command[1]", "install",
                "factory.dev-factory.runtime.yarn.install-command[2]", "--network-timeout=600000"));
        assertThat(new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get()
                .yarn().installCommand()).containsExactly("yarn", "install", "--network-timeout=600000");

        for (var bad : java.util.List.of(
                java.util.List.of("yarn", "install", "--x; rm -rf /"),
                java.util.List.of("yarn", "install", "$(id)"),
                java.util.List.of("yarn", "in stall"),
                java.util.List.of("npm", "install")))
            assertThatThrownBy(() -> new DevRuntimeProperties.Yarn(null, null, bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("install command");
    }

    @Test
    void verificationCommandRunsTheInstallBeforeAYarnPlanAndLeavesMavenAlone() {
        var plans = Map.of("unit", java.util.List.of("mvn", "-B", "test"),
                "jest", java.util.List.of("yarn", "test", "--maxWorkers=2"));
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN), null, 0);

        assertThat(runtime.verificationCommand("unit")).containsExactly("mvn", "-B", "test");
        assertThat(runtime.installCommand("unit")).isEmpty();
        assertThat(runtime.installCommand("jest")).isEqualTo(DevRuntimeProperties.Yarn.DEFAULT_INSTALL_COMMAND);
        assertThat(runtime.verificationCommand("jest")).containsExactly("sh", "-c",
                "'yarn' 'install' '--frozen-lockfile' '--ignore-scripts' '--non-interactive'"
                        + " '--mutex' 'file:/tmp/factory-home/.yarn-cache/.yarn-mutex' && 'yarn' 'test' '--maxWorkers=2'");
    }

    @Test
    void theDefaultInstallLocksInsideTheCachePathTheWorkloadsMount() {
        var install = DevRuntimeProperties.Yarn.DEFAULT_INSTALL_COMMAND;

        assertThat(install).containsSequence("--mutex", "file:" + DockerWorkloads.YARN_CACHE + "/.yarn-mutex");
    }

    @Test
    void resolvingInstallIsTheConfiguredInstallWithoutTheFrozenFlagAndMavenIsUnaffected() {
        var plans = Map.of("unit", java.util.List.of("mvn", "-B", "test"),
                "jest", java.util.List.of("yarn", "test", "--maxWorkers=2"));
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN), null, 0);

        assertThat(runtime.installCommand("jest", InstallMode.FROZEN)).isEqualTo(runtime.installCommand("jest"));
        assertThat(runtime.installCommand("jest", InstallMode.RESOLVE))
                .containsExactly("yarn", "install", "--ignore-scripts", "--non-interactive",
                        "--mutex", "file:/tmp/factory-home/.yarn-cache/.yarn-mutex");
        assertThat(runtime.verificationCommand("jest", InstallMode.RESOLVE)).containsExactly("sh", "-c",
                "'yarn' 'install' '--ignore-scripts' '--non-interactive'"
                        + " '--mutex' 'file:/tmp/factory-home/.yarn-cache/.yarn-mutex' && 'yarn' 'test' '--maxWorkers=2'");
        assertThat(runtime.verificationCommand("jest", InstallMode.FROZEN)).isEqualTo(runtime.verificationCommand("jest"));
        assertThat(runtime.verificationCommand("unit", InstallMode.RESOLVE)).containsExactly("mvn", "-B", "test");
    }

    @Test
    void quotedTokensReachTheShellAsOneArgumentEach() throws Exception {
        var probe = new ProcessBuilder("sh", "-c", "exit 0");
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(probe.start().waitFor() == 0, "sh is not available");
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "sh is not available");
        }
        var tokens = java.util.List.of("printf", "<%s>\\n", "it's; echo pwned", "$(echo expanded)", "a b", "`id`");

        // What the shell does with the string, not what the string looks like.
        var process = new ProcessBuilder("sh", "-c", DevRuntimeProperties.shell(tokens)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        assertThat(output).isEqualTo("<it's; echo pwned>\n<$(echo expanded)>\n<a b>\n<`id`>\n");
    }

    @Test
    void verificationCommandQuotesEveryPlanTokenWithTheShellIdiomForSingleQuotes() {
        var plans = Map.of("jest", java.util.List.of("yarn", "test", "it's"));
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN), null, 0);

        assertThat(runtime.verificationCommand("jest").get(2)).endsWith("&& 'yarn' 'test' 'it'\\''s'");
    }

    @Test
    void changingTheInstallChangesTheCommandSoOldReceiptsNoLongerMatch() {
        var plans = Map.of("jest", java.util.List.of("yarn", "test"));
        var toolchains = Map.of("jest", Toolchain.YARN);
        var before = new DevRuntimeProperties(plans, null, 0, null, Map.of(), toolchains, null, 0);
        var after = new DevRuntimeProperties(plans, null, 0, null, Map.of(), toolchains,
                new DevRuntimeProperties.Yarn(null, null, java.util.List.of("yarn", "install", "--ignore-scripts")), 0);

        assertThat(after.verificationCommand("jest")).isNotEqualTo(before.verificationCommand("jest"));
    }
}
