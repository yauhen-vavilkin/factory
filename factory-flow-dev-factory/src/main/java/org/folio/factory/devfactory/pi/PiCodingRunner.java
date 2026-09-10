package org.folio.factory.devfactory.pi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    ProcessOutputSink sink = (stream, bytes, offset, length) -> {
      if (stream != ProcessOutputSink.Stream.STDOUT) return;
      String chunk = new String(bytes, offset, length, StandardCharsets.UTF_8);
      raw.append(chunk);
      lines.append(chunk);
      int newline;
      while ((newline = lines.indexOf("\n")) >= 0) {
        String line = lines.substring(0, newline).strip();
        lines.delete(0, newline + 1);
        if (!line.isEmpty()) {
          try { events.add(json.readTree(line)); } catch (RuntimeException ignored) { }
        }
      }
    };
    ProcessSession session = sessions.open(sandbox,
        new ProcessSessionRequest(argv, cwd, environment, timeout, maxOutputBytes), sink);
    try (session) {
      session.stdin().write((taskJson + "\n").getBytes(StandardCharsets.UTF_8));
      session.stdin().flush();
      int exit = session.awaitExit().join();
      boolean settled = events.stream().anyMatch(e -> "agent_settled".equals(e.path("type").asString("")));
      return new CodingAttempt(exit, settled, raw.toString(), List.copyOf(events),
          session.stdoutBytes(), session.stderrBytes());
    } catch (IOException e) {
      throw new IllegalStateException("Pi RPC write failed", e);
    }
  }

  public record CodingAttempt(int processExitCode, boolean settled, String rawExchange,
                              List<JsonNode> events, long stdoutBytes, long stderrBytes) { }
}
