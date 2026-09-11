package org.folio.factory.devfactory.pi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    StringBuilder lines = new StringBuilder();
    List<JsonNode> events = new ArrayList<>();
    Object monitor = new Object();
    ProcessOutputSink sink = (stream, bytes, offset, length) -> {
      synchronized (monitor) {
        String chunk = new String(bytes, offset, length, StandardCharsets.UTF_8);
        raw.append(chunk);
        if (stream != ProcessOutputSink.Stream.STDOUT) return;
        lines.append(chunk);
        int newline;
        while ((newline = lines.indexOf("\n")) >= 0) {
          String line = lines.substring(0, newline).strip();
          lines.delete(0, newline + 1);
          if (!line.isEmpty()) {
            try { events.add(json.readTree(line)); } catch (RuntimeException ignored) { }
          }
        }
        monitor.notifyAll();
      }
    };
    ProcessSession session = sessions.open(sandbox,
        new ProcessSessionRequest(argv, cwd, environment, timeout, maxOutputBytes), sink);
    try (session) {
      send(session, command("get_state"));
      waitForResponse(events, monitor, "get_state", timeout);
      send(session, prompt(taskJson));
      long deadline = System.nanoTime() + timeout.toNanos();
      synchronized (monitor) {
        while (!hasEvent(events, "agent_settled") && System.nanoTime() < deadline) {
          try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
      }
      boolean settled = hasEvent(events, "agent_settled");
      if (settled) {
        send(session, command("get_state"));
        send(session, command("get_session_stats"));
        send(session, command("get_last_assistant_text"));
      }
      try { session.stdin().close(); } catch (IOException ignored) { }
      int exit;
      try { exit = session.awaitExit().get(Math.min(timeout.toMillis(), 10_000), TimeUnit.MILLISECONDS); }
      catch (TimeoutException e) { session.killWorkload(); exit = -1; }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); session.killWorkload(); exit = -1; }
      catch (ExecutionException e) { session.killWorkload(); exit = -1; }
      synchronized (monitor) {
        if (lines.length() > 0) raw.append(lines);
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
  private JsonNode command(String name) { var n = json.createObjectNode(); n.put("type", name); return n; }
  private JsonNode prompt(String task) { var n = json.createObjectNode(); n.put("type", "prompt"); n.put("message", task); return n; }
  private static boolean hasEvent(List<JsonNode> events, String type) {
    return events.stream().anyMatch(e -> type.equals(e.path("type").asString("")));
  }
  private static void waitForResponse(List<JsonNode> events, Object monitor, String command, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (events.stream().noneMatch(e -> command.equals(e.path("command").asString("")))
          && System.nanoTime() < deadline) {
        try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      }
    }
  }

  public record CodingAttempt(int processExitCode, boolean settled, String rawExchange,
                              List<JsonNode> events, long stdoutBytes, long stderrBytes) { }
}
