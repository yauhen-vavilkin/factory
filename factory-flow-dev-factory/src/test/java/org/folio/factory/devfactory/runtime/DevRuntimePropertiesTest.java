package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevRuntimePropertiesTest {
    @Test
    void independentProfilesKeepPiLegacyFallbacksAndExactClaudeModel() {
        var values = new java.util.HashMap<String, Object>();
        String prefix = "factory.dev-factory.runtime.coding.";
        values.put(prefix + "runtime", "claude-code");
        values.put(prefix + "image", "legacy:image");
        values.put(prefix + "provider", "legacy-provider");
        values.put(prefix + "model", "legacy-model");
        values.put(prefix + "api-key", "legacy-key");
        values.put(prefix + "pi.model", "new-pi-model");
        values.put(prefix + "pi.api-key", "new-pi-key");
        values.put(prefix + "pi.base-url", "");
        values.put(prefix + "pi.max-output-tokens", "8192");
        values.put(prefix + "claude-code.model", "operator/Custom-Model-2026");
        values.put(prefix + "claude-code.base-url", "http://gateway.example/anthropic");
        values.put(prefix + "claude-code.authentication", "bearer-token");
        values.put(prefix + "claude-code.auth-token", "claude-secret");
        var coding = bind(values).coding();
        assertThat(coding.piProfile().image()).isEqualTo("legacy:image");
        assertThat(coding.piProfile().provider()).isEqualTo("legacy-provider");
        assertThat(coding.piProfile().model()).isEqualTo("new-pi-model");
        assertThat(coding.piProfile().apiKey()).isEqualTo("new-pi-key");
        assertThat(coding.piProfile().maxOutputTokens()).isEqualTo(8192);
        assertThat(coding.claudeCode().model()).isEqualTo("operator/Custom-Model-2026");
        coding.claudeCode().requireConfigured();
        assertThat(coding.toString() + coding.piProfile() + coding.claudeCode())
                .doesNotContain("legacy-key", "new-pi-key", "claude-secret", "gateway.example");
        var selected = new org.folio.factory.devfactory.DevFactoryConfiguration().selectCodingRuntime(bind(values));
        assertThat(selected).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(selected.identity()).containsEntry("model", "operator/Custom-Model-2026");
    }

    @Test
    void inactiveProfilesAreNotValidatedAndSelectionIsClosed() {
        var configuration = new org.folio.factory.devfactory.DevFactoryConfiguration();
        var empty = bind(Map.of("factory.dev-factory.runtime.coding.runtime", "claude-code",
                "factory.dev-factory.runtime.coding.pi.max-output-tokens", "-1"));
        assertThat(configuration.selectCodingRuntime(empty)).isInstanceOf(ClaudeCodeCodingRuntime.class);
        assertThat(configuration.selectCodingRuntime(new DevRuntimeProperties(null, null, 0, null)))
                .isInstanceOf(PiCodingRuntime.class);
        assertThatThrownBy(() -> configuration.selectCodingRuntime(bind(Map.of(
                "factory.dev-factory.runtime.coding.runtime", "unknown"))))
                .hasMessageContaining("Unsupported coding runtime");
    }

    @Test
    void claudeRejectsWrongProtocolAndRequiresSelectedCredentialBeforeExecution() {
        var config = new DevRuntimeProperties.ClaudeCode(null, "openai-completions", "http://gateway",
                "model", "api-key", "secret", null, null, null, null);
        var workload = org.mockito.Mockito.mock(DockerWorkloads.Workload.class);
        assertThatThrownBy(() -> new ClaudeCodeCodingRuntime(config).code(workload, null, 30))
                .hasMessageContaining("api=anthropic-messages");
        org.mockito.Mockito.verifyNoInteractions(workload);
        for (String auth : new String[] {null, "", "oauth", "bearer-token"}) {
            var missing = new DevRuntimeProperties.ClaudeCode(null, null, "http://gateway", "model",
                    auth, "api-key-is-not-a-token", null, null, null, null);
            assertThatThrownBy(missing::requireConfigured).isInstanceOf(IllegalStateException.class);
        }
    }

    private static DevRuntimeProperties bind(Map<String, ?> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
    }

    @Test
    void imagesAreOffUnlessThePiProfileEnablesThem() {
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.runtime", "pi")).coding().piProfile().images()).isFalse();
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.pi.images", "true")).coding().piProfile().images()).isTrue();
    }

    @Test
    void claudeCodeImagesAreOffUnlessEnabled() {
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.runtime", "claude-code")).coding().claudeCode().images()).isFalse();
        assertThat(bind(Map.of("factory.dev-factory.runtime.coding.claude-code.images", "true")).coding().claudeCode().images()).isTrue();
    }

    @Test
    void attachmentLimitsDefaultToTenImagesAndThreeMegabytes() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.attachments.max-images", "", "factory.dev-factory.attachments.max-bytes", "")));
        var limits = binder.bind("factory.dev-factory.attachments", DevAttachmentProperties.class)
                .orElseGet(DevAttachmentProperties::new);

        assertThat(limits.maxImages()).isEqualTo(10);
        assertThat(limits.maxBytes()).isEqualTo(3L * 1024 * 1024);
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of("factory.dev-factory.attachments.max-images", "0")))
                .bind("factory.dev-factory.attachments", DevAttachmentProperties.class).get().maxImages()).isZero();
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of("factory.dev-factory.attachments.max-images", "5")))
                .bind("factory.dev-factory.attachments", DevAttachmentProperties.class).get().maxImages()).isEqualTo(5);
    }

    @Test
    void bindsCodingSettingsFromSpringConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.timeout-seconds", "60",
                "factory.dev-factory.runtime.coding.image", "coding:image",
                "factory.dev-factory.runtime.coding.provider", "codemie",
                "factory.dev-factory.runtime.coding.model", "gemini-3.8-flash",
                "factory.dev-factory.runtime.coding.base-url", "http://host.docker.internal:4001/v1",
                "factory.dev-factory.runtime.coding.api", "openai-completions",
                "factory.dev-factory.runtime.coding.api-key", "test-key",
                "factory.dev-factory.runtime.coding.runtime", "OTHER",
                "factory.dev-factory.runtime.coding.reasoning-effort", "high",
                "factory.dev-factory.runtime.coding.max-output-tokens", "65536"));

        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        var coding = properties.coding();
        assertThat(coding.image()).isEqualTo("coding:image");
        assertThat(coding.provider()).isEqualTo("codemie");
        assertThat(coding.model()).isEqualTo("gemini-3.8-flash");
        assertThat(coding.baseUrl()).isEqualTo("http://host.docker.internal:4001/v1");
        assertThat(coding.api()).isEqualTo("openai-completions");
        assertThat(coding.apiKey()).isEqualTo("test-key");
        assertThat(coding.runtime()).isEqualTo("other");
        assertThat(coding.reasoningEffort()).isEqualTo("high");
        assertThat(coding.maxOutputTokens()).isEqualTo(65536);
        coding.requireConfigured();
    }

    @Test
    void bindsPlainOpenAiCompatibleProfileWithUnchangedDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.coding.image", "pi:image",
                "factory.dev-factory.runtime.coding.provider", "openai-compatible",
                "factory.dev-factory.runtime.coding.model", "test-model",
                "factory.dev-factory.runtime.coding.base-url", "https://api.example.com/v1",
                "factory.dev-factory.runtime.coding.api-key", "test-key"));
        var coding = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().coding();
        assertThat(coding.provider()).isEqualTo("openai-compatible");
        assertThat(coding.model()).isEqualTo("test-model");
        assertThat(coding.api()).isEqualTo("openai-completions");
        assertThat(coding.reasoningEffort()).isNull();
        assertThat(coding.maxOutputTokens()).isEqualTo(16384);
    }

    @Test
    void mavenCacheHasStableDefaultAndAcceptsOperatorOverride() {
        var defaults = new DevRuntimeProperties(null, null, 0, null);
        assertThat(defaults.mavenCacheVolume()).isEqualTo("factory-dev-m2-cache");
        assertThat(defaults.coding().runtime()).isEqualTo("pi");
        assertThat(defaults.coding().reasoningEffort()).isNull();
        assertThat(defaults.coding().maxOutputTokens()).isEqualTo(16384);
        assertThat(new DevRuntimeProperties(null, null, 0, "operator-cache").mavenCacheVolume())
                .isEqualTo("operator-cache");
    }

    @Test
    void codingRuntimeSelectionIsNormalizedForAdapterLookup() {
        var coding = new DevRuntimeProperties.Coding("image", "provider", "model", null, null, "key", " Pi ", null, null);
        assertThat(coding.runtime()).isEqualTo("pi");
    }

    @Test
    void rejectsNamesThatCannotBeDockerVolumeNames() {
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, "bad/name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsTrustedRequiredReportPathsAndRejectsTraversal() {
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.required-reports.roles[0]",
                "target/failsafe-reports/TEST-org.folio.roles.it.CacheConfigIT.xml"));
        var properties = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get();
        assertThat(properties.requiredReports("roles")).containsExactly(
                "target/failsafe-reports/TEST-org.folio.roles.it.CacheConfigIT.xml");
        assertThatThrownBy(() -> new DevRuntimeProperties(null, null, 0, null,
                Map.of("roles", java.util.List.of("../target/failsafe-reports/TEST-a.xml"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plansDefaultToMavenAndBindAnExplicitToolchain() {
        var plans = Map.of("unit", java.util.List.of("mvn", "test"));
        assertThat(new DevRuntimeProperties(plans, null, 0, null).toolchain("unit")).isEqualTo(Toolchain.MAVEN);
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
        var yarn = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN));
        assertThat(yarn.toolchain("jest")).isEqualTo(Toolchain.YARN);
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.plans.jest[0]", "yarn",
                "factory.dev-factory.runtime.plan-toolchains.jest", "yarn"));
        assertThat(new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get()
                .toolchain("jest")).isEqualTo(Toolchain.YARN);
        assertThatThrownBy(() -> new DevRuntimeProperties(plans, null, 0, null,
                Map.of("jest", java.util.List.of("target/surefire-reports/TEST-a.xml")),
                Map.of("jest", Toolchain.YARN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jest");
    }

    @Test
    void rejectsToolchainsForUnknownPlansOrUnknownEcosystems() {
        assertThatThrownBy(() -> new DevRuntimeProperties(Map.of("unit", java.util.List.of("mvn", "test")),
                null, 0, null, Map.of(), Map.of("typo", Toolchain.MAVEN)))
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
        var defaults = new DevRuntimeProperties(null, null, 0, null);
        assertThat(defaults.yarn().cacheVolume()).isEqualTo("factory-dev-yarn-cache");
        assertThat(defaults.yarn().folioRegistry()).isEqualTo("https://repository.folio.org/repository/npm-folioci/");
        var source = new MapConfigurationPropertySource(Map.of(
                "factory.dev-factory.runtime.yarn.cache-volume", "ops-yarn",
                "factory.dev-factory.runtime.yarn.folio-registry", "https://npm.example.org/folio/"));
        var yarn = new Binder(source).bind("factory.dev-factory.runtime", DevRuntimeProperties.class).get().yarn();
        assertThat(yarn.cacheVolume()).isEqualTo("ops-yarn");
        assertThat(yarn.folioRegistry()).isEqualTo("https://npm.example.org/folio/");
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn("bad/name", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn(null, "https://npm.example.org/'; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevRuntimeProperties.Yarn(null, "ftp://npm.example.org/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void environmentFollowsThePlansToolchain() {
        var plans = Map.of("unit", java.util.List.of("mvn", "test"), "jest", java.util.List.of("yarn", "test"));
        var runtime = new DevRuntimeProperties(plans, null, 0, "operator-m2", Map.of(), Map.of("jest", Toolchain.YARN));

        assertThat(runtime.environment("unit")).isEqualTo(BuildEnvironment.maven("operator-m2"));
        assertThat(runtime.environment("jest")).isEqualTo(new BuildEnvironment(Toolchain.YARN,
                "factory-dev-yarn-cache", "https://repository.folio.org/repository/npm-folioci/"));
    }

    @Test
    void seedTimeoutDefaultsBindsAndFallsBackWhenNotPositive() {
        assertThat(new DevRuntimeProperties(Map.of(), null, 0, null).seedTimeoutSeconds()).isEqualTo(300);
        assertThat(bind(Map.of("factory.dev-factory.runtime.seed-timeout-seconds", "900")).seedTimeoutSeconds())
                .isEqualTo(900);
        assertThat(bind(Map.of("factory.dev-factory.runtime.seed-timeout-seconds", "0")).seedTimeoutSeconds())
                .isEqualTo(DevRuntimeProperties.DEFAULT_SEED_TIMEOUT_SECONDS);
        assertThat(bind(Map.of("factory.dev-factory.runtime.timeout-seconds", "60")).seedTimeoutSeconds())
                .isEqualTo(300);
    }

    @Test
    void yarnInstallCommandHasADefaultBindsAndIsValidated() {
        assertThat(new DevRuntimeProperties.Yarn(null, null).installCommand())
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
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN));

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
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN));

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
        var runtime = new DevRuntimeProperties(plans, null, 0, null, Map.of(), Map.of("jest", Toolchain.YARN));

        assertThat(runtime.verificationCommand("jest").get(2)).endsWith("&& 'yarn' 'test' 'it'\\''s'");
    }

    @Test
    void changingTheInstallChangesTheCommandSoOldReceiptsNoLongerMatch() {
        var plans = Map.of("jest", java.util.List.of("yarn", "test"));
        var toolchains = Map.of("jest", Toolchain.YARN);
        var before = new DevRuntimeProperties(plans, null, 0, null, Map.of(), toolchains, null);
        var after = new DevRuntimeProperties(plans, null, 0, null, Map.of(), toolchains,
                new DevRuntimeProperties.Yarn(null, null, java.util.List.of("yarn", "install", "--ignore-scripts")));

        assertThat(after.verificationCommand("jest")).isNotEqualTo(before.verificationCommand("jest"));
    }
}
