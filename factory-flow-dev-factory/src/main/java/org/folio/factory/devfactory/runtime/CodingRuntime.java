package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    /**
     * Runtime metrics may report {@code inputTokens}, {@code cacheReadTokens},
     * {@code cacheWriteTokens}, {@code outputTokens}, and {@code costUsd}.
     * Token fields are independent and optional so runtimes only report the
     * categories their provider exposes.
     */
    record Result(String summary, java.util.Map<String, Object> metrics) {
        public Result(String summary) { this(summary, java.util.Map.of()); }
    }
    Result code(DockerWorkloads.Workload workload, String task, int timeoutSeconds);
    default Result code(DockerWorkloads.Workload workload, String task, int timeoutSeconds,
                        java.util.function.Consumer<java.util.Map<String, Object>> progress) {
        return code(workload, task, timeoutSeconds);
    }
}
