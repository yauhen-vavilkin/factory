package org.folio.factory.sandbox.api;

public interface SandboxService {

  SandboxHandle create(SandboxSpec spec);

  CommandResult exec(SandboxHandle handle, String command, long timeoutSec);

  void teardown(SandboxHandle handle);
}
