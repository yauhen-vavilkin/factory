package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                   String mavenCacheVolume, Map<String, List<String>> requiredReports,
                                   Map<String, Toolchain> planToolchains, Yarn yarn, int seedTimeoutSeconds) {
    public static final String DEFAULT_MAVEN_CACHE_VOLUME = "factory-dev-m2-cache";
    /** Time allowed for copying the shared dependency cache into one new coding or verification workload. */
    public static final int DEFAULT_SEED_TIMEOUT_SECONDS = 300;
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, Map.of());
    }
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume, Map<String, List<String>> requiredReports) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, requiredReports, Map.of());
    }
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume, Map<String, List<String>> requiredReports,
                                Map<String, Toolchain> planToolchains) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, requiredReports, planToolchains, null);
    }
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume, Map<String, List<String>> requiredReports,
                                Map<String, Toolchain> planToolchains, Yarn yarn) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, requiredReports, planToolchains, yarn, 0);
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        planToolchains = planToolchains == null ? Map.of() : Map.copyOf(planToolchains);
        var unknownPlans = new java.util.TreeSet<>(planToolchains.keySet());
        unknownPlans.removeAll(plans.keySet());
        if (!unknownPlans.isEmpty())
            throw new IllegalArgumentException("plan-toolchains names plans that are not configured: " + unknownPlans);
        requiredReports = requiredReports == null ? Map.of() : requiredReports.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        for (var reports : requiredReports.values()) for (String report : reports) {
            if (!report.matches("(?:[A-Za-z0-9_.-]+/)*target/(?:surefire|failsafe)-reports/TEST-[A-Za-z0-9_.$-]+\\.xml")
                    || java.util.Arrays.asList(report.split("/")).contains("..")) {
                throw new IllegalArgumentException("Invalid required Maven report path");
            }
        }
        for (var plan : requiredReports.keySet())
            if (planToolchains.getOrDefault(plan, Toolchain.MAVEN) != Toolchain.MAVEN)
                throw new IllegalArgumentException("required-reports apply only to Maven plans: " + plan);
        coding = coding == null ? new Coding(null, null, null, null, null, null, null, null, null) : coding;
        timeoutSeconds = timeoutSeconds <= 0 ? 1800 : timeoutSeconds;
        mavenCacheVolume = mavenCacheVolume == null || mavenCacheVolume.isBlank()
                ? DEFAULT_MAVEN_CACHE_VOLUME : mavenCacheVolume;
        if (!mavenCacheVolume.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid Maven cache volume name");
        yarn = yarn == null ? new Yarn(null, null) : yarn;
        seedTimeoutSeconds = seedTimeoutSeconds <= 0 ? DEFAULT_SEED_TIMEOUT_SECONDS : seedTimeoutSeconds;
    }
    public List<String> command(String id) {
        var command = plans.get(id);
        if (command == null || command.isEmpty()) throw new IllegalStateException("Missing trusted verification plan: " + id);
        return List.copyOf(command);
    }
    public Toolchain toolchain(String id) {
        return planToolchains.getOrDefault(id, Toolchain.MAVEN);
    }
    public List<String> requiredReports(String id) {
        return requiredReports.getOrDefault(id, List.of());
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
                .collect(java.util.stream.Collectors.joining(" "));
    }
    public record Yarn(String cacheVolume, String folioRegistry, List<String> installCommand) {
        public static final String DEFAULT_CACHE_VOLUME = "factory-dev-yarn-cache";
        public static final String DEFAULT_FOLIO_REGISTRY = "https://repository.folio.org/repository/npm-folioci/";
        /** Two starting builds installing into the shared cache at once corrupt each other's entries; the lock lives in the cache. */
        public static final List<String> DEFAULT_INSTALL_COMMAND = List.of(
                "yarn", "install", "--frozen-lockfile", "--ignore-scripts", "--non-interactive",
                "--mutex", "file:" + DockerWorkloads.YARN_CACHE + "/.yarn-mutex");
        private static final java.util.regex.Pattern INSTALL_TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9_.=:/@+,-]+");
        public Yarn(String cacheVolume, String folioRegistry) {
            this(cacheVolume, folioRegistry, null);
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Yarn {
            cacheVolume = first(cacheVolume, DEFAULT_CACHE_VOLUME);
            folioRegistry = first(folioRegistry, DEFAULT_FOLIO_REGISTRY);
            installCommand = installCommand == null || installCommand.isEmpty()
                    ? DEFAULT_INSTALL_COMMAND : List.copyOf(installCommand);
            if (!BuildEnvironment.VOLUME.matcher(cacheVolume).matches())
                throw new IllegalArgumentException("Invalid yarn cache volume name");
            if (!BuildEnvironment.REGISTRY.matcher(folioRegistry).matches())
                throw new IllegalArgumentException("Invalid FOLIO npm registry");
            // The tokens are joined into a shell command, so only plain argument characters pass.
            if (!installCommand.stream().allMatch(token -> token != null && INSTALL_TOKEN.matcher(token).matches())
                    || !"yarn".equals(installCommand.get(0)))
                throw new IllegalArgumentException("Invalid yarn install command");
        }
    }
    public record Coding(String image, String provider, String model, String baseUrl, String api, String apiKey,
                         String runtime, String reasoningEffort, Integer maxOutputTokens,
                         Pi pi, ClaudeCode claudeCode) {
        public Coding(String image, String provider, String model, String baseUrl, String api, String apiKey,
                      String runtime, String reasoningEffort, Integer maxOutputTokens) {
            this(image, provider, model, baseUrl, api, apiKey, runtime, reasoningEffort, maxOutputTokens, null, null);
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Coding {
            api = api == null ? "openai-completions" : api;
            runtime = runtime == null || runtime.isBlank() ? "pi" : runtime.strip().toLowerCase(java.util.Locale.ROOT);
            reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                    ? null : reasoningEffort.strip().toLowerCase(java.util.Locale.ROOT);
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
            claudeCode = claudeCode == null
                    ? new ClaudeCode(null, null, null, null, null, null, null, null, null, null) : claudeCode;
        }
        @Override public String toString() {
            return "Coding[runtime=" + runtime + ", image=" + image + ", provider=" + provider + ", model=" + model + "]";
        }
        public void requireConfigured() {
            piProfile().requireConfigured();
        }
        /** Nonblank profile values override legacy Pi settings, field by field. */
        public Pi piProfile() {
            var profile = pi == null ? new Pi(null, null, null, null, null, null, null, null, null, false) : pi;
            return new Pi(first(profile.image(), image), first(profile.provider(), provider),
                    first(profile.model(), model), first(profile.baseUrl(), baseUrl), first(profile.api(), api),
                    first(profile.apiKey(), apiKey), first(profile.authentication(), "api-key"),
                    first(profile.reasoningEffort(), reasoningEffort),
                    profile.maxOutputTokens() == null ? maxOutputTokens : profile.maxOutputTokens(),
                    profile.images());
        }
    }

    /** {@code images} declares that the model reads images; Pi otherwise swaps every image for a text placeholder. */
    public record Pi(String image, String provider, String model, String baseUrl, String api, String apiKey,
                     String authentication, String reasoningEffort, Integer maxOutputTokens, boolean images) {
        public void requireConfigured() {
            // Built-in API types in the pinned Pi 0.85.1 image (pi-ai KnownApi).
            if (api == null || !List.of("openai-completions", "openai-responses", "anthropic-messages",
                    "mistral-conversations", "azure-openai-responses", "openai-codex-responses",
                    "bedrock-converse-stream", "google-generative-ai", "google-vertex", "pi-messages").contains(api))
                throw new IllegalStateException("Unsupported Pi API type; configure a built-in API supported by Pi 0.85.1");
            if (!"api-key".equals(authentication))
                throw new IllegalStateException("Pi authentication must be api-key");
            required("pi", image, provider, model, apiKey);
            if (maxOutputTokens == null || maxOutputTokens <= 0)
                throw new IllegalStateException("Pi max-output-tokens must be positive");
        }
        @Override public String toString() { return "Pi[image=" + image + ", model=" + model + "]"; }
    }

    /** {@code images} declares that the configured model reads images; only then are the ticket's images passed on. */
    public record ClaudeCode(String image, String api, String baseUrl, String model, String authentication,
                             String apiKey, String authToken, Integer maxTurns, Integer maxOutputTokens,
                             String effort, boolean images) {
        public ClaudeCode(String image, String api, String baseUrl, String model, String authentication,
                          String apiKey, String authToken, Integer maxTurns, Integer maxOutputTokens,
                          String effort) {
            this(image, api, baseUrl, model, authentication, apiKey, authToken, maxTurns, maxOutputTokens, effort, false);
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public ClaudeCode {
            image = first(image, "factory-dev-claude-code:2.1.287-java21-node22");
            api = first(api, "anthropic-messages");
            maxTurns = maxTurns == null ? 40 : maxTurns;
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
        }
        public void requireConfigured() {
            if (!"anthropic-messages".equals(api))
                throw new IllegalStateException("Claude Code requires api=anthropic-messages; OpenAI API endpoints need a different runtime");
            if (!"api-key".equals(authentication) && !"bearer-token".equals(authentication))
                throw new IllegalStateException("Claude Code authentication must explicitly select api-key or bearer-token");
            required("claude-code", image, baseUrl, model, secret());
            java.net.URI endpoint;
            try { endpoint = java.net.URI.create(baseUrl); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Claude Code base-url must be an HTTP(S) endpoint"); }
            if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getFragment() != null)
                throw new IllegalStateException("Claude Code base-url must be HTTP(S), without credentials, query or fragment");
            if (maxTurns <= 0 || maxOutputTokens <= 0)
                throw new IllegalStateException("Claude Code max-turns and max-output-tokens must be positive");
            if (effort != null && !effort.isBlank() && !List.of("low", "medium", "high", "max").contains(effort))
                throw new IllegalStateException("Claude Code effort must be low, medium, high or max");
        }
        public String secret() { return "bearer-token".equals(authentication) ? authToken : apiKey; }
        @Override public String toString() { return "ClaudeCode[image=" + image + ", model=" + model + "]"; }
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static void required(String profile, String... values) {
        if (java.util.Arrays.stream(values).anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalStateException("Configure factory.dev-factory.runtime.coding." + profile
                    + " image, model, endpoint/provider and selected authentication credential");
    }
}
