package org.folio.factory.sandbox.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.folio.factory.sandbox.tools.Shell;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "factory.sandbox.mode", havingValue = "local")
public class LocalSandboxService implements SandboxService {

  private static final long CLONE_TIMEOUT_SEC = 300L;
  private static final String RETAINED_MARKER = ".factory-retained";

  private final SandboxProperties properties;
  private final Clock clock;

  public LocalSandboxService(SandboxProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public SandboxHandle create(SandboxSpec spec) {
    String sandboxId = "sbx-" + spec.taskId();
    Path workspace = properties.workspaceRoot().resolve(sanitize(sandboxId)).toAbsolutePath();
    String cloneCommand = "git clone " + Shell.quote(spec.repoUrl()) + " repo && cd repo && git checkout -b "
        + Shell.quote(spec.branch()) + " " + Shell.quote("origin/" + spec.baseBranch());
    boolean workspaceCreated = false;
    try {
      Files.createDirectories(properties.workspaceRoot());
      sweepExpiredWorkspaces();
      deleteRecursively(workspace);
      Files.createDirectories(workspace);
      workspaceCreated = true;
      ProcessOutput output = runCommand(workspace, cloneCommand, CLONE_TIMEOUT_SEC);
      if (output.timedOut()) {
        throw new SandboxException("Git clone timed out after " + CLONE_TIMEOUT_SEC + "s in "
            + workspace + ": " + output.stderr());
      }
      if (output.exitCode() != 0) {
        throw new SandboxException("Git clone failed in " + workspace + ", exit code "
            + output.exitCode() + ": " + output.stderr());
      }
      return new SandboxHandle(sandboxId, workspace.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deleteWorkspaceQuietly(workspaceCreated, workspace);
      throw new SandboxException("Sandbox creation interrupted for task " + spec.taskId(), e);
    } catch (IOException e) {
      deleteWorkspaceQuietly(workspaceCreated, workspace);
      throw new SandboxException("Failed to create sandbox for task " + spec.taskId(), e);
    } catch (RuntimeException e) {
      deleteWorkspaceQuietly(workspaceCreated, workspace);
      throw e;
    }
  }

  @Override
  public CommandResult exec(SandboxHandle handle, String command, long timeoutSec) {
    long startNanos = System.nanoTime();
    try {
      ProcessOutput output = runCommand(Path.of(handle.containerId()), command, timeoutSec);
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
      if (output.timedOut()) {
        String stderr = output.stderr() + "\n[sandbox exec timed out after " + timeoutSec + "s]";
        return new CommandResult(-1, output.stdout(), stderr, durationMs);
      }
      return new CommandResult(output.exitCode(), output.stdout(), output.stderr(), durationMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Execution interrupted in sandbox " + handle.sandboxId(), e);
    } catch (IOException e) {
      throw new SandboxException("Failed to execute command in sandbox " + handle.sandboxId(), e);
    }
  }

  @Override
  public void teardown(SandboxHandle handle) {
    Path workspace = Path.of(handle.containerId());
    try {
      if (properties.workspaceRetention().isZero() || properties.workspaceRetention().isNegative()) {
        deleteRecursively(workspace);
        return;
      }
      Files.writeString(workspace.resolve(RETAINED_MARKER), Long.toString(clock.millis()));
    } catch (NoSuchFileException e) {
    } catch (IOException e) {
      throw new SandboxException("Failed to tear down sandbox " + handle.sandboxId(), e);
    }
  }

  private void sweepExpiredWorkspaces() throws IOException {
    long retentionMillis = properties.workspaceRetention().toMillis();
    long now = clock.millis();
    try (Stream<Path> entries = Files.list(properties.workspaceRoot())) {
      for (Path dir : entries.filter(Files::isDirectory).toList()) {
        Path marker = dir.resolve(RETAINED_MARKER);
        if (!Files.isRegularFile(marker)) {
          continue;
        }
        Long retainedEpoch = readRetainedEpoch(marker);
        if (retainedEpoch == null || retainedEpoch < now - retentionMillis) {
          deleteRecursively(dir);
        }
      }
    }
  }

  private static Long readRetainedEpoch(Path marker) {
    try {
      return Long.valueOf(Files.readString(marker).trim());
    } catch (IOException | NumberFormatException e) {
      return null;
    }
  }

  private static void deleteWorkspaceQuietly(boolean workspaceCreated, Path workspace) {
    if (!workspaceCreated) {
      return;
    }
    try {
      deleteRecursively(workspace);
    } catch (IOException e) {
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> entries = Files.walk(dir)) {
      for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.delete(path);
        } catch (NoSuchFileException e) {
        }
      }
    } catch (NoSuchFileException e) {
    }
  }

  private ProcessOutput runCommand(Path workspace, String command, long timeoutSec)
      throws IOException, InterruptedException {
    Process process = new ProcessBuilder("/bin/sh", "-c", command)
        .directory(workspace.toFile())
        .start();
    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();
    Thread stdoutReader = startDrainer(process.getInputStream(), stdout);
    Thread stderrReader = startDrainer(process.getErrorStream(), stderr);
    boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5L, TimeUnit.SECONDS);
      stdoutReader.join(TimeUnit.SECONDS.toMillis(5L));
      stderrReader.join(TimeUnit.SECONDS.toMillis(5L));
      return new ProcessOutput(-1, stdout.toString(), stderr.toString(), true);
    }
    stdoutReader.join(TimeUnit.SECONDS.toMillis(5L));
    stderrReader.join(TimeUnit.SECONDS.toMillis(5L));
    return new ProcessOutput(process.exitValue(), stdout.toString(), stderr.toString(), false);
  }

  private static Thread startDrainer(InputStream stream, StringBuilder buffer) {
    Thread thread = new Thread(() -> drain(stream, buffer), "sandbox-stream-drainer");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void drain(InputStream stream, StringBuilder buffer) {
    try (InputStream in = stream) {
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
    }
  }

  private static String sanitize(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private record ProcessOutput(int exitCode, String stdout, String stderr, boolean timedOut) {
  }
}
