package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

/** Operator-owned commands; task text never supplies executable configuration. */
@ConfigurationProperties(prefix = "factory.dev-factory.runtime")
public record DevRuntimeProperties(Map<String, List<String>> plans, Coding coding, int timeoutSeconds) {
    public DevRuntimeProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        coding = coding == null ? new Coding(null, null, null, null, null, null) : coding;
        timeoutSeconds = timeoutSeconds <= 0 ? 1800 : timeoutSeconds;
    }
    public List<String> command(String id) {
        var command = plans.get(id);
        if (command == null || command.isEmpty()) throw new IllegalStateException("Missing trusted verification plan: " + id);
        return List.copyOf(command);
    }
    public record Coding(String image, String provider, String model, String baseUrl, String api, String apiKey) {
        public Coding { api = api == null ? "openai-completions" : api; }
        @Override public String toString() { return "Coding[image=" + image + ", provider=" + provider + ", model=" + model + "]"; }
        public void requireConfigured() {
            if (image == null || provider == null || model == null || apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("Configure factory.dev-factory.runtime.coding.{image,provider,model,api-key}");
        }
    }
}
