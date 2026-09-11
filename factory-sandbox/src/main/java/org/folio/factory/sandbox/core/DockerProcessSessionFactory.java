package org.folio.factory.sandbox.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.folio.factory.sandbox.api.ProcessOutputSink;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionFactory;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.exception.SandboxException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Docker exec transport: stdin is a pipe and stdout/stderr are captured concurrently. */
@Component
@ConditionalOnProperty(name = "factory.sandbox.mode", havingValue = "docker", matchIfMissing = true)
public final class DockerProcessSessionFactory implements ProcessSessionFactory {
  private final DockerClient docker;

  public DockerProcessSessionFactory(DockerClient docker) {
    this.docker = docker;
  }

  @Override
  public ProcessSession open(SandboxHandle handle, ProcessSessionRequest request,
                             ProcessOutputSink sink) {
    try {
      PipedOutputStream input = new PipedOutputStream();
      PipedInputStream dockerInput = new PipedInputStream(input, 64 * 1024);
      var create = docker.execCreateCmd(handle.containerId())
          .withCmd(request.argv().toArray(String[]::new)).withWorkingDir(request.cwd())
          .withEnv(request.environment().entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue()).toList())
          .withAttachStdin(true).withAttachStdout(true).withAttachStderr(true).withTty(false)
          .exec();
      ExecStartCmd start = docker.execStartCmd(create.getId()).withDetach(false).withTty(false)
          .withStdIn(dockerInput);
      DockerSession session = new DockerSession(docker, handle.containerId(), create.getId(), input, request, sink);
      start.exec(session.callback());
      session.deadline.schedule(session::killWorkload, request.timeout().toMillis(), TimeUnit.MILLISECONDS);
      return session;
    } catch (IOException | RuntimeException e) {
      throw new SandboxException("Unable to start Docker process session", e);
    }
  }

  private static final class DockerSession implements ProcessSession {
    private final DockerClient docker;
    private final String containerId;
    private final String execId;
    private final OutputStream input;
    private final ProcessSessionRequest request;
    private final ProcessOutputSink sink;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "factory-docker-session-deadline");
      t.setDaemon(true);
      return t;
    });
    private volatile long stdoutBytes;
    private volatile long stderrBytes;
    private ExecStartResultCallback callback;

    DockerSession(DockerClient docker, String containerId, String execId, OutputStream input,
                  ProcessSessionRequest request, ProcessOutputSink sink) {
      this.docker = docker; this.containerId = containerId; this.execId = execId; this.input = input;
      this.request = request; this.sink = sink == null ? (s, b, o, l) -> { } : sink;
    }

    ExecStartResultCallback callback() {
      callback = new ExecStartResultCallback() {
        @Override public void onNext(Frame frame) {
          byte[] bytes = frame.getPayload();
          ProcessOutputSink.Stream stream = frame.getStreamType() == StreamType.STDERR
              ? ProcessOutputSink.Stream.STDERR : ProcessOutputSink.Stream.STDOUT;
          long used = stream == ProcessOutputSink.Stream.STDOUT ? stdoutBytes : stderrBytes;
          int accepted = (int) Math.min(bytes.length, Math.max(0, request.maxOutputBytes() - used));
          if (accepted > 0) {
            sink.accept(stream, bytes, 0, accepted);
            if (stream == ProcessOutputSink.Stream.STDOUT) stdoutBytes += accepted;
            else stderrBytes += accepted;
          }
        }
        @Override public void onComplete() {
          try { complete(inspectExit()); }
          finally { super.onComplete(); }
        }
        @Override public void onError(Throwable throwable) {
          exit.completeExceptionally(throwable);
          deadline.shutdownNow();
        }
      };
      return callback;
    }

    private void complete(int code) {
      if (exit.complete(code)) deadline.shutdownNow();
    }

    private int inspectExit() {
      Integer code = docker.inspectExecCmd(findExecId()).exec().getExitCode();
      return code == null ? -1 : code;
    }

    // The callback is started for this exec; Docker's inspect endpoint is not needed
    // for normal completion by callers. A closed stream is represented as -1.
    private String findExecId() { return execId; }
    @Override public OutputStream stdin() { return input; }
    @Override public CompletableFuture<Integer> awaitExit() { return exit; }
    @Override public void terminate() { docker.killContainerCmd(containerId).exec(); complete(-1); }
    @Override public void killWorkload() {
      if (Boolean.TRUE.equals(docker.inspectContainerCmd(containerId).exec().getState().getRunning())) {
        docker.killContainerCmd(containerId).exec();
      }
      complete(-1);
    }
    @Override public long stdoutBytes() { return stdoutBytes; }
    @Override public long stderrBytes() { return stderrBytes; }
    @Override public void close() {
      try {
        killWorkload();
      } finally {
        deadline.shutdownNow();
        try { input.close(); } catch (IOException ignored) { }
        try { callback.close(); } catch (IOException ignored) { }
      }
    }
  }
}
