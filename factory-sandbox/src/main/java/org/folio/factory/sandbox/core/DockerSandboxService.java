package org.folio.factory.sandbox.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.folio.factory.sandbox.tools.Shell;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "factory.sandbox.mode", havingValue = "docker",
    matchIfMissing = true)
public class DockerSandboxService implements SandboxService {

  private static final String DEFAULT_IMAGE = "factory-pi:jdk21";
  private static final String WORKSPACE_DIR = "/workspace";
  private static final long CLONE_TIMEOUT_SEC = 300L;

  private final DockerClient dockerClient;
  private final String image;
  private final String dockerNetwork;

  public DockerSandboxService(DockerClient dockerClient) {
    this.dockerClient = dockerClient;
    this.image = DEFAULT_IMAGE;
    this.dockerNetwork = null;
  }

  @Autowired
  public DockerSandboxService(DockerClient dockerClient, SandboxProperties properties) {
    this.dockerClient = dockerClient;
    this.image = properties.image();
    this.dockerNetwork = properties.dockerNetwork();
  }

  @Override
  public SandboxHandle create(SandboxSpec spec) {
    String containerId = null;
    try {
      HostConfig hostConfig = new HostConfig().withNetworkMode(dockerNetwork)
              .withNanoCPUs(2_000_000_000L)
              .withMemory(4L * 1024 * 1024 * 1024)
              .withPidsLimit(512L)
              .withCapDrop(Capability.ALL)
              .withSecurityOpts(java.util.List.of("no-new-privileges:true"));
      String source = localSource(spec.repoUrl());
      if (source != null) {
        hostConfig.withBinds(new Bind(source, new Volume("/workspace/source")));
      }
      CreateContainerResponse container = dockerClient.createContainerCmd(image)
          .withCmd("sleep", "infinity")
          .withWorkingDir(WORKSPACE_DIR)
          .withHostConfig(hostConfig)
          .exec();
      containerId = container.getId();
      dockerClient.startContainerCmd(containerId).exec();
      String cloneCommand = source == null
          ? "git clone --depth 1 " + Shell.quote(spec.repoUrl()) + " repo && cd repo && git checkout -b "
              + Shell.quote(spec.branch()) + " " + Shell.quote(spec.baseBranch())
          : "mkdir repo && cp -a /workspace/source/. repo/ && cd repo && git checkout -b "
              + Shell.quote(spec.branch()) + " " + Shell.quote(spec.baseBranch());
      ExecOutput output = runExec(containerId, cloneCommand, CLONE_TIMEOUT_SEC);
      if (output.exitCode() != 0) {
        throw new SandboxException(
            "Git clone failed in container " + containerId + ", exit code " + output.exitCode() + ": "
                + output.stderr());
      }
      return new SandboxHandle("sbx-" + spec.taskId(), containerId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      removeContainerQuietly(containerId);
      throw new SandboxException("Sandbox creation interrupted for task " + spec.taskId(), e);
    } catch (RuntimeException e) {
      removeContainerQuietly(containerId);
      if (e instanceof SandboxException sandboxEx) {
        throw sandboxEx;
      }
      throw new SandboxException("Failed to create sandbox for task " + spec.taskId(), e);
    }
  }

  private static String localSource(String repoUrl) {
    if (repoUrl == null || !repoUrl.startsWith("file://")) return null;
    try {
      Path path = Path.of(java.net.URI.create(repoUrl)).toAbsolutePath().normalize();
      if (!java.nio.file.Files.isDirectory(path)) throw new SandboxException("local repository is not a directory");
      return path.toString();
    } catch (IllegalArgumentException e) {
      throw new SandboxException("invalid local repository URL", e);
    }
  }

  @Override
  public CommandResult exec(SandboxHandle handle, String command, long timeoutSec) {
    long startNanos = System.nanoTime();
    try {
      ExecOutput output = runExec(handle.containerId(), command, timeoutSec);
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
      return new CommandResult(output.exitCode(), output.stdout(), output.stderr(), durationMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SandboxException("Execution interrupted in sandbox " + handle.sandboxId(), e);
    } catch (RuntimeException e) {
      throw new SandboxException("Failed to execute command in sandbox " + handle.sandboxId(), e);
    }
  }

  @Override
  public void teardown(SandboxHandle handle) {
    try {
      dockerClient.removeContainerCmd(handle.containerId())
          .withForce(true)
          .withRemoveVolumes(true)
          .exec();
    } catch (NotFoundException e) {
    }
  }

  private void removeContainerQuietly(String containerId) {
    if (containerId == null) {
      return;
    }
    try {
      dockerClient.removeContainerCmd(containerId)
          .withForce(true)
          .withRemoveVolumes(true)
          .exec();
    } catch (RuntimeException e) {
    }
  }

  private ExecOutput runExec(String containerId, String command, long timeoutSec) throws InterruptedException {
    String execId = dockerClient.execCreateCmd(containerId)
        .withCmd("/bin/sh", "-c", command)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .exec()
        .getId();

    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();
    ExecStartResultCallback callback = dockerClient.execStartCmd(execId)
        .exec(new FrameCollectingCallback(stdout, stderr));
    callback.awaitCompletion(timeoutSec, TimeUnit.SECONDS);

    Integer exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCode();
    return new ExecOutput(exitCode != null ? exitCode : -1, stdout.toString(), stderr.toString());
  }

  private record ExecOutput(int exitCode, String stdout, String stderr) {
  }

  private static final class FrameCollectingCallback extends ExecStartResultCallback {

    private final StringBuilder stdout;
    private final StringBuilder stderr;

    FrameCollectingCallback(StringBuilder stdout, StringBuilder stderr) {
      this.stdout = stdout;
      this.stderr = stderr;
    }

    @Override
    public void onNext(Frame frame) {
      String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
      if (frame.getStreamType() == StreamType.STDERR) {
        stderr.append(payload);
      } else {
        stdout.append(payload);
      }
    }
  }
}
