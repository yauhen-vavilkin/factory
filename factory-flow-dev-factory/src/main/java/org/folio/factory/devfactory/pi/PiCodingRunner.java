package org.folio.factory.devfactory.pi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import org.folio.factory.sandbox.api.ProcessOutputSink;
import org.folio.factory.sandbox.api.ProcessSession;
import org.folio.factory.sandbox.api.ProcessSessionFactory;
import org.folio.factory.sandbox.api.ProcessSessionRequest;
import org.folio.factory.sandbox.api.SandboxHandle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small replaceable adapter around Pi's native RPC process. */
public final class PiCodingRunner {
  private final ProcessSessionFactory sessions;
  private final JsonMapper json = JsonMapper.builder().build();

  public PiCodingRunner(ProcessSessionFactory sessions) { this.sessions = sessions; }

  public CodingAttempt run(SandboxHandle sandbox, List<String> argv, String cwd,
                           String taskJson, Duration timeout, long maxOutputBytes,
                           Map<String, String> environment) {
    StringBuilder raw = new StringBuilder();
    List<JsonNode> events = new ArrayList<>();
    Object monitor = new Object();
    PiRpcProtocol.Decoder decoder = new PiRpcProtocol.Decoder();
    AtomicReference<RuntimeException> protocolFailure = new AtomicReference<>();
    int[] requestId = {0};
    ProcessOutputSink sink = (stream, bytes, offset, length) -> {
      synchronized (monitor) {
        raw.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
        if (stream != ProcessOutputSink.Stream.STDOUT) return;
        try { events.addAll(decoder.accept(bytes, offset, length)); }
        catch (RuntimeException e) { protocolFailure.compareAndSet(null, e); }
        monitor.notifyAll();
      }
    };
    ProcessSession session = sessions.open(sandbox,
        new ProcessSessionRequest(argv, cwd, environment, timeout, maxOutputBytes), sink);
    try (session) {
      String stateId = "factory-" + (++requestId[0]);
      send(session, command("get_state", stateId));
      waitForResponse(events, monitor, stateId, timeout, protocolFailure);
      send(session, prompt(taskJson, "factory-" + (++requestId[0])));
      long deadline = System.nanoTime() + timeout.toNanos();
      synchronized (monitor) {
        while (!hasEvent(events, "agent_settled") && protocolFailure.get() == null
            && System.nanoTime() < deadline) {
          try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
      }
      boolean settled = hasEvent(events, "agent_settled");
      if (settled) {
        send(session, command("get_state", "factory-" + (++requestId[0])));
        send(session, command("get_session_stats", "factory-" + (++requestId[0])));
        send(session, command("get_last_assistant_text", "factory-" + (++requestId[0])));
      }
      try { session.stdin().close(); } catch (IOException ignored) { }
      int exit;
      try { exit = session.awaitExit().get(Math.min(timeout.toMillis(), 10_000), TimeUnit.MILLISECONDS); }
      catch (TimeoutException e) { session.killWorkload(); exit = -1; }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); session.killWorkload(); exit = -1; }
      catch (ExecutionException e) { session.killWorkload(); exit = -1; }
      synchronized (monitor) {
        decoder.finish();
        if (protocolFailure.get() != null) throw protocolFailure.get();
        return new CodingAttempt(exit, settled, raw.toString(), List.copyOf(events),
            session.stdoutBytes(), session.stderrBytes());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Pi RPC write failed", e);
    }
  }

  private void send(ProcessSession session, JsonNode request) throws IOException {
    session.stdin().write((json.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
    session.stdin().flush();
  }
  private JsonNode command(String name, String id) { var n = json.createObjectNode(); n.put("type", name); n.put("id", id); return n; }
  private JsonNode prompt(String task, String id) { var n = json.createObjectNode(); n.put("type", "prompt"); n.put("id", id); n.put("message", task); return n; }
  private static boolean hasEvent(List<JsonNode> events, String type) {
    return events.stream().anyMatch(e -> type.equals(e.path("type").asString("")));
  }
  private static void waitForResponse(List<JsonNode> events, Object monitor, String id,
                                      Duration timeout, AtomicReference<RuntimeException> failure) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (events.stream().noneMatch(e -> id.equals(e.path("id").asString("")))
          && failure.get() == null
          && System.nanoTime() < deadline) {
        try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      }
    }
  }

  public record CodingAttempt(int processExitCode, boolean settled, String rawExchange,
                              List<JsonNode> events, long stdoutBytes, long stderrBytes) { }
}
