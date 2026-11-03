package org.folio.factory.devfactory.runtime;

import org.folio.factory.devfactory.verification.MavenTestSuite;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
            claudeCode = claudeCode == null ? new ClaudeCode(null, null) : claudeCode;
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
     * safety limit, not a tuning knob: the 30-minute coding timeout usually ends a run first.
     */
    public record ClaudeCode(String image, Integer maxTurns) {
        public static final int MAX_TURNS_LIMIT = 500;

        public ClaudeCode {
            image = first(image, "factory-dev-claude-code:2.1.287-java21");
            maxTurns = maxTurns == null ? 200 : maxTurns;
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
