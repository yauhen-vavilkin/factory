package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.verification.MavenTestSuite;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                   String mavenCacheVolume, Map<String, List<String>> requiredReports) {
    public static final String DEFAULT_MAVEN_CACHE_VOLUME = "factory-dev-m2-cache";
    private static final Pattern MAVEN_REPORT_PATH = Pattern.compile(
            "(?:[A-Za-z0-9_.-]+/)*target/(?:surefire|failsafe)-reports/TEST-[A-Za-z0-9_.$-]+\\.xml");

    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, Map.of());
    }
    @ConstructorBinding
    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        requiredReports = validatedReportRequirements(requiredReports);
        coding = coding == null ? new Coding(null, null, null, null, null, null, null, null, null) : coding;
        timeoutSeconds = timeoutSeconds <= 0 ? 1800 : timeoutSeconds;
        mavenCacheVolume = mavenCacheVolume == null || mavenCacheVolume.isBlank()
                ? DEFAULT_MAVEN_CACHE_VOLUME : mavenCacheVolume;
        if (!mavenCacheVolume.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid Maven cache volume name");
        }
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

    private static Map<String, List<String>> validatedReportRequirements(Map<String, List<String>> requirements) {
        if (requirements == null) {
            return Map.of();
        }
        Map<String, List<String>> validated = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requirements.entrySet()) {
            for (String requirement : entry.getValue()) {
                validateReportRequirement(requirement);
            }
            validated.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(validated);
    }

    private static void validateReportRequirement(String requirement) {
        if (MavenTestSuite.isSuiteRequirement(requirement)) {
            return;
        }
        boolean validLegacyPath = MAVEN_REPORT_PATH.matcher(requirement).matches()
                && !Arrays.asList(requirement.split("/")).contains("..");
        if (!validLegacyPath) {
            throw new IllegalArgumentException("Required Maven reports must name surefire, failsafe or a valid report path");
        }
    }

    public record Coding(String image, String provider, String model, String baseUrl, String api, String apiKey,
                         String runtime, String reasoningEffort, Integer maxOutputTokens,
                         Pi pi, ClaudeCode claudeCode) {
        public Coding(String image, String provider, String model, String baseUrl, String api, String apiKey,
                      String runtime, String reasoningEffort, Integer maxOutputTokens) {
            this(image, provider, model, baseUrl, api, apiKey, runtime, reasoningEffort, maxOutputTokens, null, null);
        }
        @ConstructorBinding
        public Coding {
            api = api == null ? "openai-completions" : api;
            runtime = normalized(first(runtime, "claude-code"));
            reasoningEffort = normalized(reasoningEffort);
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
            claudeCode = claudeCode == null
                    ? new ClaudeCode(null, null, null, null, null, null, null, null, null, null) : claudeCode;
        }
        @Override
        public String toString() {
            return "Coding[runtime=" + runtime + ", image=" + image + ", provider=" + provider + ", model=" + model + "]";
        }
        public void requireConfigured() {
            piProfile().requireConfigured();
        }
        /** Nonblank profile values override legacy Pi settings, field by field. */
        public Pi piProfile() {
            var profile = pi == null ? new Pi(null, null, null, null, null, null, null, null, null) : pi;
            Integer effectiveMaxOutputTokens = profile.maxOutputTokens();
            if (effectiveMaxOutputTokens == null) {
                effectiveMaxOutputTokens = maxOutputTokens;
            }
            return new Pi(first(profile.image(), image), first(profile.provider(), provider),
                    first(profile.model(), model), first(profile.baseUrl(), baseUrl), first(profile.api(), api),
                    first(profile.apiKey(), apiKey), first(profile.authentication(), "api-key"),
                    first(profile.reasoningEffort(), reasoningEffort),
                    effectiveMaxOutputTokens);
        }
    }

    public record Pi(String image, String provider, String model, String baseUrl, String api, String apiKey,
                     String authentication, String reasoningEffort, Integer maxOutputTokens) {
        public void requireConfigured() {
            // Built-in API types in the pinned Pi 0.85.1 image (pi-ai KnownApi).
            if (api == null || !List.of("openai-completions", "openai-responses", "anthropic-messages",
                    "mistral-conversations", "azure-openai-responses", "openai-codex-responses",
                    "bedrock-converse-stream", "google-generative-ai", "google-vertex", "pi-messages").contains(api)) {
                throw new IllegalStateException("Unsupported Pi API type; configure a built-in API supported by Pi 0.85.1");
            }
            if (!"api-key".equals(authentication)) {
                throw new IllegalStateException("Pi authentication must be api-key");
            }
            required("pi", image, provider, model, apiKey);
            if (maxOutputTokens == null || maxOutputTokens <= 0) {
                throw new IllegalStateException("Pi max-output-tokens must be positive");
            }
        }

        @Override
        public String toString() {
            return "Pi[image=" + image + ", model=" + model + "]";
        }
    }

    public record ClaudeCode(String image, String api, String baseUrl, String model, String authentication,
                             String apiKey, String authToken, Integer maxTurns, Integer maxOutputTokens,
                             String effort) {
        public ClaudeCode {
            image = first(image, "factory-dev-claude-code:2.1.287-java21");
            api = first(api, "anthropic-messages");
            model = first(model, "claude-sonnet-5-5");
            maxTurns = maxTurns == null ? 40 : maxTurns;
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
        }
        public void requireConfigured() {
            if (!"anthropic-messages".equals(api)) {
                throw new IllegalStateException("Claude Code requires api=anthropic-messages; OpenAI API endpoints need a different runtime");
            }
            if (!"api-key".equals(authentication) && !"bearer-token".equals(authentication)) {
                throw new IllegalStateException("Claude Code authentication must explicitly select api-key or bearer-token");
            }
            required("claude-code", image, baseUrl, model, secret());
            requireValidEndpoint();
            if (maxTurns <= 0 || maxOutputTokens <= 0) {
                throw new IllegalStateException("Claude Code max-turns and max-output-tokens must be positive");
            }
            if (effort != null && !effort.isBlank() && !List.of("low", "medium", "high", "max").contains(effort)) {
                throw new IllegalStateException("Claude Code effort must be low, medium, high or max");
            }
        }

        private void requireValidEndpoint() {
            URI endpoint;
            try {
                endpoint = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Claude Code base-url must be an HTTP(S) endpoint");
            }
            boolean http = "http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme());
            if (!http || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalStateException("Claude Code base-url must be HTTP(S), without credentials, query or fragment");
            }
        }

        public String secret() {
            return "bearer-token".equals(authentication) ? authToken : apiKey;
        }

        @Override
        public String toString() {
            return "ClaudeCode[image=" + image + ", model=" + model + "]";
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
