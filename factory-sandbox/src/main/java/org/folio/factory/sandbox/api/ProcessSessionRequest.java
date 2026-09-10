package org.folio.factory.sandbox.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Trusted argv-only request for one long-lived process inside a sandbox. */
public record ProcessSessionRequest(List<String> argv, String cwd, Map<String, String> environment,
                                    Duration timeout, long maxOutputBytes) {
  public ProcessSessionRequest {
    if (argv == null || argv.isEmpty() || argv.stream().anyMatch(a -> a == null || a.isBlank())) {
      throw new IllegalArgumentException("argv must contain non-blank arguments");
    }
    if (cwd == null || cwd.isBlank() || timeout == null || timeout.isNegative() || timeout.isZero()
        || maxOutputBytes <= 0) {
      throw new IllegalArgumentException("cwd, positive timeout and output bound are required");
    }
    argv = List.copyOf(argv);
    environment = environment == null ? Map.of() : Map.copyOf(environment);
  }
}
