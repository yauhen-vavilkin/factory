package org.folio.factory.sandbox.api;

public interface ProcessSessionFactory {
  ProcessSession open(SandboxHandle handle, ProcessSessionRequest request,
                      ProcessOutputSink outputSink);
}
