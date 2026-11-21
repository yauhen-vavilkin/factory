package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.verification.MavenTestSuite;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                   String mavenCacheVolume, Map<String, List<String>> requiredReports,
                                   Map<String, Toolchain> planToolchains, Yarn yarn, int seedTimeoutSeconds) {
    public static final String DEFAULT_MAVEN_CACHE_VOLUME = "factory-dev-m2-cache";
    /** Time allowed for copying the shared dependency cache into one new coding or verification workload. */
    public static final int DEFAULT_SEED_TIMEOUT_SECONDS = 300;

    /** Maven plans only, with the default yarn settings and seed timeout. */
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume, Map<String, List<String>> requiredReports) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, requiredReports, Map.of(), null, 0);
    }

    @ConstructorBinding
    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        planToolchains = validatedToolchains(planToolchains, plans);
        requiredReports = validatedReportRequirements(requiredReports, planToolchains);
        coding = coding == null ? new Coding(null, null, null) : coding;
        timeoutSeconds = timeoutSeconds <= 0 ? 3600 : timeoutSeconds;
        mavenCacheVolume = mavenCacheVolume == null || mavenCacheVolume.isBlank()
                ? DEFAULT_MAVEN_CACHE_VOLUME : mavenCacheVolume;
        if (!BuildEnvironment.VOLUME.matcher(mavenCacheVolume).matches()) {
            throw new IllegalArgumentException("Invalid Maven cache volume name");
        }
        yarn = yarn == null ? new Yarn(null, null, null) : yarn;
        seedTimeoutSeconds = seedTimeoutSeconds <= 0 ? DEFAULT_SEED_TIMEOUT_SECONDS : seedTimeoutSeconds;
    }

    public List<String> command(String id) {
        var command = plans.get(id);
        if (command == null || command.isEmpty()) {
            throw new IllegalStateException("Missing trusted verification plan: " + id);
        }
        return List.copyOf(command);
    }

    public List<String> requiredReports(String id) {
        return requiredReports.getOrDefault(id, List.of());
    }

    public Toolchain toolchain(String id) {
        return planToolchains.getOrDefault(id, Toolchain.MAVEN);
    }

    /** The trusted dependency cache and tool configuration for workloads that run this plan. */
    public BuildEnvironment environment(String id) {
        return switch (toolchain(id)) {
            case MAVEN -> BuildEnvironment.maven(mavenCacheVolume);
            case YARN -> new BuildEnvironment(Toolchain.YARN, yarn.cacheVolume(), yarn.folioRegistry());
        };
    }

    /**
     * The yarn install, defined once: the starting build and verification run it before the
     * plan's test command, and the coding container runs it before the agent starts.
     */
    public List<String> installCommand(String id) {
        return toolchain(id) == Toolchain.YARN ? yarn.installCommand() : List.of();
    }

    /** The install for a lockfile mode: RESOLVE is the configured install without {@code --frozen-lockfile}. */
    public List<String> installCommand(String id, InstallMode mode) {
        var install = installCommand(id);
        return mode == InstallMode.FROZEN ? install
                : install.stream().filter(token -> !"--frozen-lockfile".equals(token)).toList();
    }

    /** What the starting build and independent verification execute: install (if any), then the plan. */
    public List<String> verificationCommand(String id) {
        return verificationCommand(id, InstallMode.FROZEN);
    }

    public List<String> verificationCommand(String id, InstallMode mode) {
        var plan = command(id);
        var install = installCommand(id, mode);
        return install.isEmpty() ? plan : List.of("sh", "-c", shell(install) + " && " + shell(plan));
    }

    static String shell(List<String> argv) {
        return argv.stream().map(token -> "'" + token.replace("'", "'\\''") + "'")
                .collect(Collectors.joining(" "));
    }

    private static Map<String, Toolchain> validatedToolchains(Map<String, Toolchain> toolchains,
                                                              Map<String, List<String>> plans) {
        if (toolchains == null) {
            return Map.of();
        }
        var unknownPlans = new TreeSet<>(toolchains.keySet());
        unknownPlans.removeAll(plans.keySet());
        if (!unknownPlans.isEmpty()) {
            throw new IllegalArgumentException("plan-toolchains names plans that are not configured: " + unknownPlans);
        }
        return Map.copyOf(toolchains);
    }

    private static Map<String, List<String>> validatedReportRequirements(Map<String, List<String>> requirements,
                                                                         Map<String, Toolchain> toolchains) {
        if (requirements == null) {
            return Map.of();
        }
        Map<String, List<String>> validated = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requirements.entrySet()) {
            if (toolchains.getOrDefault(entry.getKey(), Toolchain.MAVEN) != Toolchain.MAVEN) {
                throw new IllegalArgumentException("required-reports apply only to Maven plans: " + entry.getKey());
            }
            for (String requirement : entry.getValue()) {
                validateReportRequirement(requirement);
            }
            validated.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(validated);
    }

    private static void validateReportRequirement(String requirement) {
        if (!MavenTestSuite.isSuiteRequirement(requirement)) {
            throw new IllegalArgumentException("Required Maven reports must name surefire or failsafe");
        }
    }

    public record Yarn(String cacheVolume, String folioRegistry, List<String> installCommand) {
        public static final String DEFAULT_CACHE_VOLUME = "factory-dev-yarn-cache";
        public static final String DEFAULT_FOLIO_REGISTRY = "https://repository.folio.org/repository/npm-folioci/";
        /**
         * Two starting builds installing into the shared cache at once corrupt each other's entries; the lock
         * lives in the cache.
         */
        public static final List<String> DEFAULT_INSTALL_COMMAND = List.of(
                "yarn", "install", "--frozen-lockfile", "--ignore-scripts", "--non-interactive",
                "--mutex", "file:" + DockerWorkloads.YARN_CACHE + "/.yarn-mutex");
        private static final Pattern INSTALL_TOKEN = Pattern.compile("[A-Za-z0-9_.=:/@+,-]+");

        public Yarn {
            cacheVolume = first(cacheVolume, DEFAULT_CACHE_VOLUME);
            folioRegistry = first(folioRegistry, DEFAULT_FOLIO_REGISTRY);
            installCommand = installCommand == null || installCommand.isEmpty()
                    ? DEFAULT_INSTALL_COMMAND : List.copyOf(installCommand);
            if (!BuildEnvironment.VOLUME.matcher(cacheVolume).matches()) {
                throw new IllegalArgumentException("Invalid yarn cache volume name");
            }
            if (!BuildEnvironment.REGISTRY.matcher(folioRegistry).matches()) {
                throw new IllegalArgumentException("Invalid FOLIO npm registry");
            }
            // The tokens are joined into a shell command, so only plain argument characters pass.
            if (!installCommand.stream().allMatch(token -> token != null && INSTALL_TOKEN.matcher(token).matches())
                    || !"yarn".equals(installCommand.get(0))) {
                throw new IllegalArgumentException("Invalid yarn install command");
            }
        }
    }

    public record Coding(String runtime, Pi pi, ClaudeCode claudeCode) {
        public Coding {
            runtime = normalized(first(runtime, "claude-code"));
            pi = pi == null ? new Pi(null, null, null, null, null, null, null, null, null, false) : pi;
            claudeCode = claudeCode == null ? new ClaudeCode(null, null) : claudeCode;
        }
    }

    /** {@code images} declares that the model reads images; Pi otherwise swaps every image for a text placeholder. */
    public record Pi(String image, String provider, String model, String baseUrl, String api, String apiKey,
                     String authentication, String reasoningEffort, Integer maxOutputTokens, boolean images) {
        public Pi {
            image = first(image, "factory-dev-pi:0.85.1-java21-node22");
            api = first(api, "openai-completions");
            authentication = first(authentication, "api-key");
            reasoningEffort = first(reasoningEffort, null);
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
        }

        public void requireConfigured() {
            if (!PiCodingRuntime.APIS.contains(api)) {
                throw new IllegalStateException(
                        "Unsupported Pi API type; configure a built-in API supported by Pi 0.85.1");
            }
            if (!"api-key".equals(authentication)) {
                throw new IllegalStateException("Pi authentication must be api-key");
            }
            required("pi", image, provider, model, apiKey);
            if (maxOutputTokens <= 0) {
                throw new IllegalStateException("Pi max-output-tokens must be positive");
            }
        }

        @Override
        public String toString() {
            return "Pi[image=" + image + ", model=" + model + "]";
        }
    }

    /**
     * The model, endpoint and secret of a Claude Code run come from config/developer-models.yaml. maxTurns is a
     * safety limit, not a tuning knob: the 60-minute coding timeout usually ends a run first.
     */
    public record ClaudeCode(String image, Integer maxTurns) {
        public static final int MAX_TURNS_LIMIT = 500;

        public ClaudeCode {
            image = first(image, "factory-dev-claude-code:2.1.287-java21-node22");
            maxTurns = maxTurns == null ? 400 : maxTurns;
        }

        public void requireConfigured() {
            if (maxTurns < 1 || maxTurns > MAX_TURNS_LIMIT) {
                throw new IllegalStateException("FACTORY_CLAUDE_CODE_MAX_TURNS is " + maxTurns
                        + ", but it must be from 1 to " + MAX_TURNS_LIMIT);
            }
        }
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static void required(String profile, String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Configure factory.dev-factory.runtime.coding." + profile
                        + " image, model, endpoint/provider and selected authentication credential");
            }
        }
    }
}
