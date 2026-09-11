package org.folio.factory.devfactory.pi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.folio.factory.sandbox.api.ProcessOutputSink;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionFactory;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.junit.jupiter.api.Test;

class PiCodingRunnerFailFastTest {
  @Test
  void failedPromptResponseFailsWithoutWaitingForSettlementTimeout() {
    FailingPromptSessions sessions = new FailingPromptSessions();
    PiCodingRunner runner = new PiCodingRunner(sessions);
    long started = System.nanoTime();

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(
        new SandboxHandle("test", "container"), List.of("pi"), "/workspace", "{}",
        Duration.ofSeconds(5), 1024 * 1024L, Map.of()));

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(error.getMessage().contains("prompt"));
    assertFalse(sessions.combinedRequests(), "model validation must precede the prompt RPC");
    assertTrue(elapsedMs < 1000, "failed RPC must not wait for the settlement deadline");
  }

  private static final class FailingPromptSessions implements ProcessSessionFactory {
    private Session session;

    @Override
    public ProcessSession open(SandboxHandle handle, ProcessSessionRequest request,
                               ProcessOutputSink sink) {
      session = new Session(sink);
      return session;
    }

    boolean combinedRequests() { return session != null && session.combinedRequests; }
  }

  private static final class Session implements ProcessSession {
    private final ProcessOutputSink sink;
    private final ByteArrayOutputStream input = new ByteArrayOutputStream();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private boolean combinedRequests;

    Session(ProcessOutputSink sink) { this.sink = sink; }

    @Override public OutputStream stdin() {
      return new OutputStream() {
        @Override public void write(int value) { input.write(value); respondIfComplete(); }
        @Override public void write(byte[] bytes, int offset, int length) {
          input.write(bytes, offset, length); respondIfComplete();
        }
      };
    }

    private void respondIfComplete() {
      String request = input.toString(java.nio.charset.StandardCharsets.UTF_8);
      if (!request.endsWith("\n")) return;
      combinedRequests |= request.contains("\"type\":\"get_state\"")
          && request.contains("\"type\":\"prompt\"");
      if (request.contains("\"type\":\"get_state\"")) {
        emit("{\"type\":\"response\",\"command\":\"get_state\",\"id\":\"factory-1\",\"success\":true}\n");
      }
      if (request.contains("\"type\":\"prompt\"")) {
        emit("{\"type\":\"response\",\"command\":\"prompt\",\"id\":\"factory-2\",\"success\":false,\"error\":\"gateway rejected\"}\n");
      }
      input.reset();
    }

    private void emit(String value) {
      byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      sink.accept(ProcessOutputSink.Stream.STDOUT, bytes, 0, bytes.length);
    }

    @Override public CompletableFuture<Integer> awaitExit() { return exit; }
    @Override public void terminate() { exit.complete(-1); }
    @Override public void killWorkload() { exit.complete(-1); }
    @Override public long stdoutBytes() { return 0; }
    @Override public long stderrBytes() { return 0; }
    @Override public void close() { }
  }
}
