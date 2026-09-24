package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    String id();

    String image();

    void requireConfigured();

    /** Non-secret display identity, available before the runtime is fully configured. */
    default java.util.Map<String, Object> identity() {
        var identity = new java.util.LinkedHashMap<String, Object>();
        String runtime = id();
        String image = image();
        if (runtime != null && !runtime.isBlank()) identity.put("runtime", runtime);
        if (image != null && !image.isBlank()) identity.put("image", image);
        return java.util.Map.copyOf(identity);
    }

    /** Removes adapter-owned credentials from persisted failures and audit activity. */
    default String redact(String value) { return value; }

    CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds);

    default CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                               java.util.function.Consumer<java.util.Map<String, Object>> progress) {
        return code(workload, request, timeoutSeconds);
    }
}
