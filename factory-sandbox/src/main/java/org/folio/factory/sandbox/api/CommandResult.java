package org.folio.factory.sandbox.api;

public record CommandResult(int exitCode, String stdout, String stderr, long durationMs) {

  public boolean ok() {
    return exitCode == 0;
  }
}
