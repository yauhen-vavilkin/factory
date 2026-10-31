package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.verification.MavenTestSuite;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                   String mavenCacheVolume, Map<String, List<String>> requiredReports) {
    public static final String DEFAULT_MAVEN_CACHE_VOLUME = "factory-dev-m2-cache";

    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        requiredReports = validatedReportRequirements(requiredReports);
        coding = coding == null ? new Coding(null, null, null) : coding;
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
        if (!MavenTestSuite.isSuiteRequirement(requirement)) {
            throw new IllegalArgumentException("Required Maven reports must name surefire or failsafe");
        }
    }

    public record Coding(String runtime, Pi pi, ClaudeCode claudeCode) {
        public Coding {
            runtime = normalized(first(runtime, "claude-code"));
            pi = pi == null ? new Pi(null, null, null, null, null, null, null, null, null) : pi;
            claudeCode = claudeCode == null
                    ? new ClaudeCode(null, null, null, null, null, null, null, null, null, null) : claudeCode;
        }
    }

    public record Pi(String image, String provider, String model, String baseUrl, String api, String apiKey,
                     String authentication, String reasoningEffort, Integer maxOutputTokens) {
        public Pi {
            image = first(image, "factory-dev-pi:0.85.1-java21");
            api = first(api, "openai-completions");
            authentication = first(authentication, "api-key");
            reasoningEffort = first(reasoningEffort, null);
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
        }

        public void requireConfigured() {
            // Built-in API types in the pinned Pi 0.85.1 image (pi-ai KnownApi).
            if (api == null || !List.of("openai-completions", "openai-responses", "anthropic-messages",
                    "mistral-conversations", "azure-openai-responses", "openai-codex-responses",
                    "bedrock-converse-stream", "google-generative-ai", "google-vertex", "pi-messages").contains(api)) {
                throw new IllegalStateException(
                        "Unsupported Pi API type; configure a built-in API supported by Pi 0.85.1");
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
            baseUrl = first(baseUrl, "http://host.docker.internal:4001");
            model = first(model, "claude-sonnet-5-5");
            authentication = first(authentication, "bearer-token");
            authToken = first(authToken, "codemie-proxy");
            maxTurns = maxTurns == null ? 40 : maxTurns;
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
        }
        public void requireConfigured() {
            if (!"anthropic-messages".equals(api)) {
                throw new IllegalStateException("Claude Code requires api=anthropic-messages; "
                        + "OpenAI API endpoints need a different runtime");
            }
            if (!"api-key".equals(authentication) && !"bearer-token".equals(authentication)) {
                throw new IllegalStateException("Claude Code authentication must be api-key or bearer-token");
            }
            required("claude-code", image, baseUrl, model, secret());
            requireValidEndpoint();
            if (maxTurns <= 0 || maxOutputTokens <= 0) {
                throw new IllegalStateException("Claude Code max-turns and max-output-tokens must be positive");
            }
            if (maxTurns > RunProfiles.MAX_TURNS) {
                throw new IllegalStateException("FACTORY_CLAUDE_CODE_MAX_TURNS is " + maxTurns
                        + ", but it must be from 1 to " + RunProfiles.MAX_TURNS);
            }
            if (effort != null && !effort.isBlank()
                    && !RunProfiles.EFFORTS.contains(effort.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Claude Code effort must be low, medium, high, xhigh or max");
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
                throw new IllegalStateException(
                        "Claude Code base-url must be HTTP(S), without credentials, query or fragment");
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
