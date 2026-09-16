package org.folio.factory.devfactory.runtime;

public interface CodingRuntime {
    record Result(String summary) { }
    Result code(DockerWorkloads.Workload workload, String task, int timeoutSeconds);
}
