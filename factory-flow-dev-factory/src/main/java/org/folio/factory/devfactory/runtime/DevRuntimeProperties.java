package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                   String mavenCacheVolume, Map<String, List<String>> requiredReports) {
    public static final String DEFAULT_MAVEN_CACHE_VOLUME = "factory-dev-m2-cache";
    public DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds,
                                String mavenCacheVolume) {
        this(plans, coding, timeoutSeconds, mavenCacheVolume, Map.of());
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        requiredReports = requiredReports == null ? Map.of() : requiredReports.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        for (var reports : requiredReports.values()) for (String report : reports) {
            if (!report.matches("(?:[A-Za-z0-9_.-]+/)*target/(?:surefire|failsafe)-reports/TEST-[A-Za-z0-9_.$-]+\\.xml")
                    || java.util.Arrays.asList(report.split("/")).contains("..")) {
                throw new IllegalArgumentException("Invalid required Maven report path");
            }
        }
        coding = coding == null ? new Coding(null, null, null, null, null, null, null, null, null) : coding;
        timeoutSeconds = timeoutSeconds <= 0 ? 1800 : timeoutSeconds;
        mavenCacheVolume = mavenCacheVolume == null || mavenCacheVolume.isBlank()
                ? DEFAULT_MAVEN_CACHE_VOLUME : mavenCacheVolume;
        if (!mavenCacheVolume.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid Maven cache volume name");
    }
    public List<String> command(String id) {
        var command = plans.get(id);
        if (command == null || command.isEmpty()) throw new IllegalStateException("Missing trusted verification plan: " + id);
        return List.copyOf(command);
    }
    public List<String> requiredReports(String id) {
        return requiredReports.getOrDefault(id, List.of());
    }
    public record Coding(String image, String provider, String model, String baseUrl, String api, String apiKey,
                         String runtime, String reasoningEffort, Integer maxOutputTokens) {
        public Coding {
            api = api == null ? "openai-completions" : api;
            runtime = runtime == null || runtime.isBlank() ? "pi" : runtime.strip().toLowerCase(java.util.Locale.ROOT);
            reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                    ? null : reasoningEffort.strip().toLowerCase(java.util.Locale.ROOT);
            maxOutputTokens = maxOutputTokens == null ? 16384 : maxOutputTokens;
            if (maxOutputTokens <= 0) throw new IllegalArgumentException("Coding max-output-tokens must be positive");
        }
        @Override public String toString() {
            return "Coding[runtime=" + runtime + ", image=" + image + ", provider=" + provider + ", model=" + model + "]";
        }
        public void requireConfigured() {
            if (image == null || provider == null || model == null || apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("Configure factory.dev-factory.runtime.coding.{image,provider,model,api-key}");
        }
    }
}
