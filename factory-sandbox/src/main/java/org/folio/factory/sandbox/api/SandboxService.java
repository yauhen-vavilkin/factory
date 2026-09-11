package org.folio.factory.sandbox.api;

public interface SandboxService {

  SandboxHandle create(SandboxSpec spec);

  CommandResult exec(SandboxHandle handle, String command, long timeoutSec);

  /** Stop coding and copy its files into a fresh base checkout for trusted export. */
  default SandboxHandle freezeForExport(SandboxHandle handle, SandboxSpec source) {
    throw new UnsupportedOperationException("Stopped-workspace export is unavailable");
  }

  void teardown(SandboxHandle handle);
}
