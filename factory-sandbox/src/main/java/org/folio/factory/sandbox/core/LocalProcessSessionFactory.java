package org.folio.factory.sandbox.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.folio.factory.sandbox.api.ProcessOutputSink;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionFactory;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.exception.SandboxException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local implementation used by deterministic protocol tests. */
@Component
@ConditionalOnProperty(name = "factory.sandbox.mode", havingValue = "local")
public final class LocalProcessSessionFactory implements ProcessSessionFactory {
  @Override
  public ProcessSession open(SandboxHandle handle, ProcessSessionRequest request,
                             ProcessOutputSink sink) {
    try {
      ProcessBuilder builder = new ProcessBuilder(request.argv()).directory(Path.of(request.cwd()).toFile())
          .redirectErrorStream(false);
      request.environment().forEach((key, value) -> builder.environment().put(key, value));
      Process process = builder.start();
      LocalSession session = new LocalSession(process, request, sink);
      session.startReaders();
      return session;
    } catch (IOException e) {
      throw new SandboxException("Unable to start process session", e);
    }
  }

  private static final class LocalSession implements ProcessSession {
    private final Process process;
    private final ProcessSessionRequest request;
    private final ProcessOutputSink sink;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private volatile long stdoutBytes;
    private volatile long stderrBytes;

    LocalSession(Process process, ProcessSessionRequest request, ProcessOutputSink sink) {
      this.process = process;
      this.request = request;
      this.sink = sink == null ? (stream, bytes, offset, length) -> { } : sink;
    }

    void startReaders() {
      reader(process.getInputStream(), ProcessOutputSink.Stream.STDOUT);
      reader(process.getErrorStream(), ProcessOutputSink.Stream.STDERR);
      Thread waiter = new Thread(() -> {
        try {
          exit.complete(process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)
              ? process.exitValue() : timeout());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          exit.completeExceptionally(e);
        }
      }, "factory-process-session-waiter");
      waiter.setDaemon(true);
      waiter.start();
    }

    private int timeout() {
      process.destroyForcibly();
      return -1;
    }

    private void reader(java.io.InputStream input, ProcessOutputSink.Stream stream) {
      Thread thread = new Thread(() -> {
        byte[] buffer = new byte[8192];
        try (input) {
          int count;
          while ((count = input.read(buffer)) >= 0) {
            long used = stream == ProcessOutputSink.Stream.STDOUT ? stdoutBytes : stderrBytes;
            int accepted = (int) Math.min(count, Math.max(0, request.maxOutputBytes() - used));
            if (accepted > 0) {
              sink.accept(stream, buffer, 0, accepted);
              if (stream == ProcessOutputSink.Stream.STDOUT) stdoutBytes += accepted;
              else stderrBytes += accepted;
            }
          }
        } catch (IOException ignored) {
          // The process may be intentionally terminated while a reader is active.
        }
      }, "factory-process-session-" + stream.name().toLowerCase());
      thread.setDaemon(true);
      thread.start();
    }

    @Override public OutputStream stdin() { return process.getOutputStream(); }
    @Override public CompletableFuture<Integer> awaitExit() { return exit; }
    @Override public void terminate() { process.destroy(); }
    @Override public void killWorkload() { process.destroyForcibly(); }
    @Override public long stdoutBytes() { return stdoutBytes; }
    @Override public long stderrBytes() { return stderrBytes; }
    @Override public void close() { killWorkload(); }
  }
}
