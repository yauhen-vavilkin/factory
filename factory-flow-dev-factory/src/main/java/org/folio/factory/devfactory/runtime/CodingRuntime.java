package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    /** Shorter secrets, such as the public word of a local proxy, also turn up in ordinary code. */
    int MIN_CHECKED_SECRET_LENGTH = 16;

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

    /**
     * True when the text holds a credential of this runtime that is long enough to be a real key, so Factory can
     * refuse a patch that would publish it.
     */
    default boolean holdsSecret(String text) { return false; }

    /** True when the text holds one of the secrets of at least {@link #MIN_CHECKED_SECRET_LENGTH} characters. */
    static boolean holdsAny(String text, java.util.Collection<String> secrets) {
        return secrets.stream().anyMatch(secret -> secret != null
                && secret.length() >= MIN_CHECKED_SECRET_LENGTH && text.contains(secret));
    }

    /** The failure message, or the exception class name when it has none; not yet redacted or bounded. */
    static String failureText(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** Only runtimes that read a conversation stream write a transcript; the others ignore the sink. */
    CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                       java.util.function.Consumer<java.util.Map<String, Object>> progress,
                       CodingTranscripts.Sink transcript);
}
