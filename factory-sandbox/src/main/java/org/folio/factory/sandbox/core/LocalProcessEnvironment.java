package org.folio.factory.sandbox.core;

import java.util.Map;
import java.util.Set;

/**
 * The environment boundary of a local sandbox process. A local child never
 * inherits the Factory JVM environment (which holds connector, delivery and
 * model credentials): its environment is cleared and rebuilt from this
 * allowlist of variables a shell, git and a JDK/Maven build need to run.
 * Explicit per-request variables are applied on top by the caller.
 */
public final class LocalProcessEnvironment {
  static final Set<String> ALLOWED = Set.of(
      "PATH", "HOME", "USER", "LOGNAME", "SHELL",
      "TMPDIR", "TMP", "TEMP",
      "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM",
      "JAVA_HOME", "MAVEN_HOME", "M2_HOME");

  private LocalProcessEnvironment() {
  }

  /** Replaces {@code child} with the allowlisted subset of {@code parent}. */
  public static void apply(Map<String, String> child, Map<String, String> parent) {
    child.clear();
    parent.forEach((name, value) -> {
      if (ALLOWED.contains(name) && value != null) {
        child.put(name, value);
      }
    });
    // A local sandbox never prompts for git credentials.
    child.put("GIT_TERMINAL_PROMPT", "0");
  }

  public static void apply(ProcessBuilder builder) {
    apply(builder.environment(), System.getenv());
  }
}
