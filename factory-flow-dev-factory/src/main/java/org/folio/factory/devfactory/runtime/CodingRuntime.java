package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    String id();

    String image();

    /**
     * Checks the configuration a run with these settings would use (null: the defaults). A
     * {@link ModelCatalog.ModelException} message is safe to show and says what to fix.
     */
    void requireConfigured(CodingRequest.RunSettings settings);

    /** Non-secret display identity of a run with these settings, available before it is fully configured. */
    default java.util.Map<String, Object> identity(CodingRequest.RunSettings settings) {
        var identity = new java.util.LinkedHashMap<String, Object>();
        String runtime = id();
        String image = image();
        if (runtime != null && !runtime.isBlank()) identity.put("runtime", runtime);
        if (image != null && !image.isBlank()) identity.put("image", image);
        return java.util.Map.copyOf(identity);
    }

    /** Removes adapter-owned credentials from persisted failures and audit activity. */
    default String redact(String value) { return value; }

    /** The failure message, or the exception class name when it has none; not yet redacted or bounded. */
    static String failureText(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** Only runtimes that read a conversation stream write a transcript; the others ignore the sink. */
    CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                       java.util.function.Consumer<java.util.Map<String, Object>> progress,
                       CodingTranscripts.Sink transcript);
}
