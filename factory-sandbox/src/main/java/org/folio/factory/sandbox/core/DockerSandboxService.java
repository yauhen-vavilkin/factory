package org.folio.factory.sandbox.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
  private static final int GIT_OUTPUT_LIMIT = 64 * 1024;
  private static final int EXEC_OUTPUT_LIMIT = 16 * 1024 * 1024;

  private final DockerClient dockerClient;
  private final String image;
  private final String dockerNetwork;
  private final String dependencyDockerNetwork;
  private final Path workspaceRoot;
  private final Map<String, Path> preparedSources = new ConcurrentHashMap<>();

  public DockerSandboxService(DockerClient dockerClient) {
    this.dockerClient = dockerClient;
    this.image = DEFAULT_IMAGE;
    this.dockerNetwork = null;
    this.dependencyDockerNetwork = null;
    this.workspaceRoot = Path.of(System.getProperty("java.io.tmpdir", "/tmp"),
        "factory-sandboxes").toAbsolutePath().normalize();
  }

  @Autowired
  public DockerSandboxService(DockerClient dockerClient, SandboxProperties properties) {
    this.dockerClient = dockerClient;
    this.image = properties.image();
    this.dockerNetwork = properties.dockerNetwork();
    this.dependencyDockerNetwork = properties.dependencyDockerNetwork();
    this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
  }

  @Override
  public SandboxHandle create(SandboxSpec spec) {
    String containerId = null;
    Path preparedSource = null;
    try {
      String policy = spec.networkPolicy();
      if (policy != null && policy.isBlank()) {
        policy = null;
      }
      String effectiveNetwork;
      if ("NONE".equals(policy)) {
        // A null Docker network mode means the default bridge, not isolation.
        effectiveNetwork = "none";
      } else if ("DEPENDENCY_ONLY".equals(policy)) {
        effectiveNetwork = dependencyDockerNetwork;
      } else if ("GATEWAY_ONLY".equals(policy)) {
        effectiveNetwork = dockerNetwork;
      } else if (policy == null) {
        effectiveNetwork = dockerNetwork;
      } else {
        throw new SandboxException("unsupported sandbox network policy: " + policy);
      }
      if ("GATEWAY_ONLY".equals(policy)
          && (dockerNetwork == null || dockerNetwork.isBlank())) {
        throw new SandboxException("GATEWAY_ONLY requires factory.sandbox.docker-network; refusing default bridge");
      }
      if ("DEPENDENCY_ONLY".equals(policy)
          && (dependencyDockerNetwork == null || dependencyDockerNetwork.isBlank())) {
        throw new SandboxException("DEPENDENCY_ONLY requires factory.sandbox.dependency-docker-network; refusing default bridge");
      }
      String effectiveImage = spec.image() == null ? image : spec.image();
      HostConfig hostConfig = new HostConfig().withNetworkMode(effectiveNetwork)
              .withReadonlyRootfs(true)
              .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,mode=1777,size=1g",
                  "/state/pi", "rw,nosuid,nodev,mode=1777,size=1g",
                  "/state/tmp", "rw,nosuid,nodev,mode=1777,size=256m",
                  "/home/agent", "rw,nosuid,nodev,mode=1777,size=1g"))
              .withNanoCPUs(2_000_000_000L)
              .withMemory(4L * 1024 * 1024 * 1024)
              .withPidsLimit(512L)
              .withCapDrop(Capability.ALL)
              .withSecurityOpts(java.util.List.of("no-new-privileges:true"));
      if ("host".equals(effectiveNetwork)) {
        hostConfig.withExtraHosts("factory-gateway:host-gateway");
      }
      String source = localSource(spec.repoUrl());
      if (source == null && ("GATEWAY_ONLY".equals(policy) || "DEPENDENCY_ONLY".equals(policy)
          || "NONE".equals(policy))) {
        preparedSource = prepareRemoteSource(spec);
        source = preparedSource.toString();
      }
      if (source != null) {
        makeReadable(source);
        hostConfig.withBinds(new Bind(source, new Volume("/workspace/source"), AccessMode.ro));
      }
      CreateContainerResponse container = dockerClient.createContainerCmd(effectiveImage)
          .withCmd("sleep", "infinity")
          .withWorkingDir(WORKSPACE_DIR)
          // Unlike tmpfs, this private anonymous volume survives workload stop.
          // teardown removes it only after the caller has exported the candidate.
          .withVolumes(new Volume(WORKSPACE_DIR))
          .withHostConfig(hostConfig)
          .exec();
      containerId = container.getId();
      dockerClient.startContainerCmd(containerId).exec();
      String settings = ("GATEWAY_ONLY".equals(policy) || "DEPENDENCY_ONLY".equals(policy))
          ? "mkdir -p /home/agent/.m2 && cp /opt/pi/maven-settings.xml /home/agent/.m2/settings.xml && "
          : "";
      String cloneCommand = "cp /opt/pi/models.json /state/pi/models.json && " + settings + (source == null
          ? "git clone --depth 1 " + Shell.quote(spec.repoUrl()) + " repo && cd repo && git checkout -b "
              + Shell.quote(spec.branch()) + " " + Shell.quote(spec.baseBranch())
          : "mkdir repo && cp -a /workspace/source/. repo/ && cd repo && git checkout -b "
              + Shell.quote(spec.branch()) + " " + Shell.quote(spec.baseBranch()));
      ExecOutput output = runExec(containerId, cloneCommand, CLONE_TIMEOUT_SEC);
      if (output.exitCode() != 0) {
        throw new SandboxException(
            "Git clone failed in container " + containerId + ", exit code " + output.exitCode() + ": "
                + output.stderr());
      }
      if (preparedSource != null) {
        preparedSources.put(containerId, preparedSource);
        preparedSource = null;
      }
      return new SandboxHandle("sbx-" + spec.taskId(), containerId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      removeContainerQuietly(containerId);
      deleteTree(preparedSource);
      throw new SandboxException("Sandbox creation interrupted for task " + spec.taskId(), e);
    } catch (RuntimeException e) {
      removeContainerQuietly(containerId);
      deleteTree(preparedSource);
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

  private static void makeReadable(String source) {
    try (var paths = java.nio.file.Files.walk(Path.of(source))) {
      paths.forEach(path -> {
        try {
          var permissions = java.nio.file.Files.getPosixFilePermissions(path);
          permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ);
          permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ);
          permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
          if (java.nio.file.Files.isDirectory(path)) {
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
          }
          java.nio.file.Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
        }
      });
    } catch (java.io.IOException e) {
      throw new SandboxException("cannot prepare local source permissions", e);
    }
  }

  /** Prepare an exact immutable source revision outside the restricted coding network. */
  private Path prepareRemoteSource(SandboxSpec spec) {
    if (spec.repoUrl() == null || !spec.repoUrl().startsWith("https://")) {
      throw new SandboxException(spec.networkPolicy() + " requires an HTTPS repository source");
    }
    java.net.URI uri;
    try {
      uri = java.net.URI.create(spec.repoUrl());
    } catch (IllegalArgumentException e) {
      throw new SandboxException("invalid repository URL", e);
    }
    if (!"github.com".equalsIgnoreCase(uri.getHost())
        && !"www.github.com".equalsIgnoreCase(uri.getHost())) {
      throw new SandboxException("GATEWAY_ONLY permits only the approved GitHub source host");
    }
    Path root = workspaceRoot.resolve("remote-" + UUID.randomUUID()).normalize();
    if (!root.startsWith(workspaceRoot)) {
      throw new SandboxException("prepared source escaped workspace root");
    }
    try {
      Files.createDirectories(workspaceRoot);
      if (Files.isSymbolicLink(workspaceRoot)) {
        throw new SandboxException("sandbox workspace root must not be a symbolic link");
      }
      Files.createDirectory(root);
      runGit(root, "git", "init", "--quiet");
      runGit(root, "git", "remote", "add", "origin", spec.repoUrl());
      runGit(root, "git", "fetch", "--depth", "1", "origin", spec.baseBranch());
      runGit(root, "git", "checkout", "--detach", "FETCH_HEAD");
      String actual = runGit(root, "git", "rev-parse", "HEAD").trim();
      if (!actual.equalsIgnoreCase(spec.baseBranch())) {
        throw new SandboxException("prepared source revision " + actual
            + " does not match requested base " + spec.baseBranch());
      }
      makeReadable(root.toString());
      return root;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deleteTree(root);
      throw new SandboxException("preparing exact repository source was interrupted", e);
    } catch (java.io.IOException | RuntimeException e) {
      deleteTree(root);
      if (e instanceof SandboxException sandboxEx) {
        throw sandboxEx;
      }
      throw new SandboxException("cannot prepare exact repository source", e);
    }
  }

  private static String runGit(Path directory, String... argv)
      throws java.io.IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(argv).directory(directory.toFile())
        .redirectErrorStream(true);
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process = builder.start();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> {
      byte[] buffer = new byte[8192];
      int capturedBytes = 0;
      try (var input = process.getInputStream()) {
        int read;
        while ((read = input.read(buffer)) != -1) {
          int remaining = GIT_OUTPUT_LIMIT - capturedBytes;
          if (remaining > 0) {
            int keep = Math.min(read, remaining);
            synchronized (captured) {
              captured.write(buffer, 0, keep);
            }
            capturedBytes += keep;
          }
        }
      } catch (java.io.IOException ignored) {
        // The process is being terminated or its output is not needed anymore.
      }
    }, "factory-git-output-reader");
    reader.setDaemon(true);
    reader.start();

    boolean timedOut = false;
    try {
      if (!process.waitFor(CLONE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        timedOut = true;
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      reader.join(5_000);
    } catch (InterruptedException e) {
      process.destroyForcibly();
      reader.interrupt();
      throw e;
    }
    final byte[] output;
    synchronized (captured) {
      output = captured.toByteArray();
    }
    String command = argv.length > 1 ? argv[1] : "unknown";
    if (timedOut) {
      throw new SandboxException("git command timed out: " + command);
    }
    if (process.exitValue() != 0) {
      throw new SandboxException("git command failed (" + command + "): "
          + new String(output, StandardCharsets.UTF_8).trim());
    }
    return new String(output, StandardCharsets.UTF_8);
  }

  private static void deleteTree(Path root) {
    if (root == null) {
      return;
    }
    try {
      if (Files.exists(root)) {
        try (var paths = Files.walk(root)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (java.io.IOException ignored) {
            }
          });
        }
      }
    } catch (java.io.IOException ignored) {
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
  public SandboxHandle freezeForExport(SandboxHandle handle, SandboxSpec source) {
    if (Boolean.TRUE.equals(dockerClient.inspectContainerCmd(handle.containerId()).exec()
        .getState().getRunning())) {
      dockerClient.killContainerCmd(handle.containerId()).exec();
    }
    if (Boolean.TRUE.equals(dockerClient.inspectContainerCmd(handle.containerId()).exec()
        .getState().getRunning())) {
      throw new SandboxException("Coding workload is still running; refusing export");
    }
    String exportNetwork = "GATEWAY_ONLY".equals(source.networkPolicy())
        ? "DEPENDENCY_ONLY" : source.networkPolicy();
    SandboxHandle exporter = create(new SandboxSpec(source.taskId() + "-export", source.repoUrl(),
        source.baseBranch(), source.branch() + "-export", source.ownerId() + "-export",
        source.image(), source.platform(), exportNetwork));
    try {
      CommandResult directory = exec(exporter, "mkdir /workspace/factory-frozen", 30);
      if (!directory.ok()) throw new SandboxException("Cannot create frozen-workspace directory");
      try (var archive = dockerClient.copyArchiveFromContainerCmd(handle.containerId(),
          "/workspace/repo").exec()) {
        dockerClient.copyArchiveToContainerCmd(exporter.containerId())
            .withRemotePath("/workspace/factory-frozen").withTarInputStream(archive).exec();
      }
      // Preserve only the fresh checkout's Git metadata. Never execute candidate
      // hooks, config or index, and remove base files to capture deletions too.
      CommandResult copy = exec(exporter, "cd repo && "
          + "find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf -- {} + && "
          + "tar -C /workspace/factory-frozen/repo --exclude=./.git -cf /tmp/factory-tree.tar . && "
          + "tar -xf /tmp/factory-tree.tar --no-same-owner", 120);
      if (!copy.ok()) throw new SandboxException("Frozen-workspace copy failed: " + copy.stderr());
      return exporter;
    } catch (java.io.IOException | RuntimeException e) {
      teardown(exporter);
      throw new SandboxException("Stopped-workspace export failed; coding volume retained", e);
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
    } finally {
      deleteTree(preparedSources.remove(handle.containerId()));
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
        .withWorkingDir(WORKSPACE_DIR)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .exec()
        .getId();

    StringBuilder stdout = new StringBuilder();
    StringBuilder stderr = new StringBuilder();
    ExecStartResultCallback callback = dockerClient.execStartCmd(execId)
        .exec(new FrameCollectingCallback(stdout, stderr, EXEC_OUTPUT_LIMIT));
    if (!callback.awaitCompletion(timeoutSec, TimeUnit.SECONDS)) {
      try {
        dockerClient.killContainerCmd(containerId).exec();
      } catch (RuntimeException ignored) {
        // The caller will report the bounded command timeout and clean up the container.
      }
      return new ExecOutput(-1, stdout.toString(), stderr.toString());
    }

    Integer exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCode();
    return new ExecOutput(exitCode != null ? exitCode : -1, stdout.toString(), stderr.toString());
  }

  private record ExecOutput(int exitCode, String stdout, String stderr) {
  }

  private static final class FrameCollectingCallback extends ExecStartResultCallback {

    private final StringBuilder stdout;
    private final StringBuilder stderr;
    private final int maxBytes;
    private int stdoutBytes;
    private int stderrBytes;

    FrameCollectingCallback(StringBuilder stdout, StringBuilder stderr, int maxBytes) {
      this.stdout = stdout;
      this.stderr = stderr;
      this.maxBytes = maxBytes;
    }

    @Override
    public void onNext(Frame frame) {
      byte[] bytes = frame.getPayload();
      if (frame.getStreamType() == StreamType.STDERR) {
        int accepted = Math.min(bytes.length, Math.max(0, maxBytes - stderrBytes));
        if (accepted > 0) {
          stderr.append(new String(bytes, 0, accepted, StandardCharsets.UTF_8));
          stderrBytes += accepted;
        }
      } else {
        int accepted = Math.min(bytes.length, Math.max(0, maxBytes - stdoutBytes));
        if (accepted > 0) {
          stdout.append(new String(bytes, 0, accepted, StandardCharsets.UTF_8));
          stdoutBytes += accepted;
        }
      }
    }
  }
}
