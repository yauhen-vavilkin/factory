package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    String id();

    String image();

    void requireConfigured();

    default java.util.Map<String, Object> identity() {
        return java.util.Map.of("runtime", id(), "image", image());
    }

    /** Removes adapter-owned credentials from persisted failures and audit activity. */
    default String redact(String value) { return value; }

    CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds);

    default CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                               java.util.function.Consumer<java.util.Map<String, Object>> progress) {
        return code(workload, request, timeoutSeconds);
    }
}
