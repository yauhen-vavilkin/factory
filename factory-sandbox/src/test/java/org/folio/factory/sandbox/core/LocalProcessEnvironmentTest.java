package org.folio.factory.sandbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.ProcessOutputSink;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A local sandbox child never sees the Factory process secrets. */
class LocalProcessEnvironmentTest {
  /** Variables a POSIX shell sets by itself; they do not come from the parent. */
  private static final Set<String> SHELL_OWN = Set.of("PWD", "OLDPWD", "SHLVL", "_", "__CF_USER_TEXT_ENCODING");

  @TempDir Path workspace;

  @Test
  void factorySecretsAreNotInheritedAndOnlyAllowlistedVariablesRemain() {
    Map<String, String> parent = new HashMap<>();
    parent.put("PATH", "/usr/bin:/bin");
    parent.put("HOME", "/home/factory");
    parent.put("TMPDIR", "/tmp/factory");
    parent.put("JAVA_HOME", "/opt/jdk21");
    parent.put("FACTORY_CONNECTORS_JIRA_API_TOKEN", "jira-secret");
    parent.put("FACTORY_CONNECTORS_JIRA_EMAIL", "bot@example.org");
    parent.put("FACTORY_DELIVERY_GITHUB_TOKEN", "delivery-secret");
    parent.put("FACTORY_CONNECTORS_GITHUB_TOKEN", "github-secret");
    parent.put("FACTORY_CONNECTORS_TESTRAIL_API_KEY", "testrail-secret");
    parent.put("FACTORY_MODEL_API_KEY", "model-secret");
    parent.put("FACTORY_MODEL_TOKEN", "model-token");
    parent.put("GLM_API_KEY", "glm-secret");
    parent.put("ANTHROPIC_API_KEY", "anthropic-secret");
    parent.put("GH_TOKEN", "gh-secret");
    parent.put("FACTORY_DB_PASSWORD", "db-secret");
    Map<String, String> child = new HashMap<>(parent);
    child.put("PREVIOUS", "value");

    LocalProcessEnvironment.apply(child, parent);

    assertThat(child).containsOnly(
        Map.entry("PATH", "/usr/bin:/bin"),
        Map.entry("HOME", "/home/factory"),
        Map.entry("TMPDIR", "/tmp/factory"),
        Map.entry("JAVA_HOME", "/opt/jdk21"),
        Map.entry("GIT_TERMINAL_PROMPT", "0"));
    assertThat(child.values()).noneMatch(value -> value.contains("secret") || value.contains("token"));
  }

  @Test
  void localProcessSessionStartsWithTheAllowlistedEnvironmentPlusRequestVariables() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ProcessSession capturing = new LocalProcessSessionFactory().open(new SandboxHandle("sbx", workspace.toString()),
        new ProcessSessionRequest(List.of("/usr/bin/env"), workspace.toString(), Map.of("PI_RUN", "1"),
            Duration.ofSeconds(30), 1_000_000),
        (stream, bytes, offset, length) -> {
          if (stream == ProcessOutputSink.Stream.STDOUT) {
            out.write(bytes, offset, length);
          }
        });
    assertThat(capturing.awaitExit().get(30, TimeUnit.SECONDS)).isZero();
    capturing.close();
    // Readers are asynchronous; give the stdout reader a moment after exit.
    for (int i = 0; i < 50 && !out.toString(StandardCharsets.UTF_8).contains("PI_RUN="); i++) {
      Thread.sleep(20);
    }

    Set<String> names = names(out.toString(StandardCharsets.UTF_8));
    assertThat(names).contains("PI_RUN", "GIT_TERMINAL_PROMPT");
    assertThat(names).allMatch(name -> LocalProcessEnvironment.ALLOWED.contains(name)
        || name.equals("PI_RUN") || name.equals("GIT_TERMINAL_PROMPT") || SHELL_OWN.contains(name));
  }

  @Test
  void localSandboxCommandsDoNotInheritTheFactoryEnvironment() {
    LocalSandboxService service = new LocalSandboxService(
        new SandboxProperties("local", null, null, workspace, Duration.ZERO, null, null, null),
        Clock.systemUTC());

    CommandResult result = service.exec(new SandboxHandle("sbx", workspace.toString()), "env", 30);

    assertThat(result.exitCode()).isZero();
    assertThat(names(result.stdout())).allMatch(name -> LocalProcessEnvironment.ALLOWED.contains(name)
        || name.equals("GIT_TERMINAL_PROMPT") || SHELL_OWN.contains(name));
  }

  private static Set<String> names(String envOutput) {
    return Arrays.stream(envOutput.split("\n"))
        .filter(line -> line.indexOf('=') > 0)
        .map(line -> line.substring(0, line.indexOf('=')))
        .collect(Collectors.toSet());
  }
}
