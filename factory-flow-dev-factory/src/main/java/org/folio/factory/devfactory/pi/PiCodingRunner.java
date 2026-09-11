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
    StringBuilder stderr = new StringBuilder();
    List<JsonNode> events = new ArrayList<>();
    Object monitor = new Object();
    PiRpcProtocol.Decoder decoder = new PiRpcProtocol.Decoder();
    AtomicReference<RuntimeException> protocolFailure = new AtomicReference<>();
    int[] requestId = {0};
    ProcessOutputSink sink = (stream, bytes, offset, length) -> {
      synchronized (monitor) {
        raw.append(new String(bytes, offset, length, StandardCharsets.UTF_8));
        if (stream != ProcessOutputSink.Stream.STDOUT) {
          if (stderr.length() < 4096) stderr.append(new String(bytes, offset,
              Math.min(length, 4096 - stderr.length()), StandardCharsets.UTF_8));
          return;
        }
        try { events.addAll(decoder.accept(bytes, offset, length)); }
        catch (RuntimeException e) { protocolFailure.compareAndSet(null, e); }
        monitor.notifyAll();
      }
    };
    ProcessSession session = sessions.open(sandbox,
        new ProcessSessionRequest(argv, cwd, environment, timeout, maxOutputBytes), sink);
    try (session) {
      String stateId = "factory-" + (++requestId[0]);
      JsonNode prompt = prompt(taskJson, "factory-" + (++requestId[0]));
      send(session, command("get_state", stateId));
      Duration rpcTimeout = timeout.compareTo(Duration.ofSeconds(10)) > 0
          ? Duration.ofSeconds(10) : timeout;
      awaitSuccessfulResponse(events, monitor, stateId, "get_state", rpcTimeout, protocolFailure);
      validateRequestedModel(snapshot(events, monitor), stateId, argv);
      send(session, prompt);
      awaitSuccessfulResponse(events, monitor, prompt.path("id").asString(), "prompt", rpcTimeout,
          protocolFailure);
      if (protocolFailure.get() != null) throw protocolFailure.get();
      long deadline = System.nanoTime() + timeout.toNanos();
      synchronized (monitor) {
        while (!hasEvent(events, "agent_settled") && protocolFailure.get() == null
            && System.nanoTime() < deadline) {
          try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
      }
      boolean settled = hasEvent(snapshot(events, monitor), "agent_settled");
      if (settled) {
        String finalStateId = "factory-" + (++requestId[0]);
        String statsId = "factory-" + (++requestId[0]);
        String assistantTextId = "factory-" + (++requestId[0]);
        send(session, command("get_state", finalStateId));
        send(session, command("get_session_stats", statsId));
        send(session, command("get_last_assistant_text", assistantTextId));
        Duration diagnosticTimeout = timeout.compareTo(Duration.ofSeconds(2)) > 0
            ? Duration.ofSeconds(2) : timeout;
        awaitDiagnosticResponse(events, monitor, finalStateId, "get_state", diagnosticTimeout,
            protocolFailure);
        awaitDiagnosticResponse(events, monitor, statsId, "get_session_stats", diagnosticTimeout,
            protocolFailure);
        awaitDiagnosticResponse(events, monitor, assistantTextId, "get_last_assistant_text",
            diagnosticTimeout, protocolFailure);
      }
      try { session.stdin().close(); } catch (IOException ignored) { }
      int exit;
      try { exit = session.awaitExit().get(Math.min(timeout.toMillis(), 10_000), TimeUnit.MILLISECONDS); }
      catch (TimeoutException e) { session.killWorkload(); exit = -1; }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); exit = -1; }
      catch (ExecutionException e) { exit = -1; }
      synchronized (monitor) {
        decoder.finish();
        if (protocolFailure.get() != null) throw protocolFailure.get();
        return new CodingAttempt(exit, settled, raw.toString(), List.copyOf(events),
            session.stdoutBytes(), session.stderrBytes());
      }
    } catch (IOException e) {
      session.killWorkload();
      throw new IllegalStateException("Pi RPC write failed: " + stderr, e);
    }
  }

  private static void awaitDiagnosticResponse(List<JsonNode> events, Object monitor, String id,
                                              String command, Duration timeout,
                                              AtomicReference<RuntimeException> failure) {
    try {
      awaitSuccessfulResponse(events, monitor, id, command, timeout, failure);
    } catch (RuntimeException ignored) {
      // Diagnostics enrich the evidence; a settled coding result does not depend on them.
    }
  }

  /** Cancellation follows Pi's queue/abort protocol before stopping the whole workload. */
  public void cancel(ProcessSession session) {
    try {
      send(session, command("clear_queue", "factory-cancel-queue"));
      send(session, command("abort", "factory-cancel-abort"));
    } catch (IOException ignored) {
      // The workload kill below is the authoritative cancellation boundary.
    } finally {
      session.killWorkload();
    }
  }

  private void send(ProcessSession session, JsonNode... requests) throws IOException {
    StringBuilder payload = new StringBuilder();
    for (JsonNode request : requests) payload.append(json.writeValueAsString(request)).append('\n');
    session.stdin().write(payload.toString().getBytes(StandardCharsets.UTF_8));
    session.stdin().flush();
  }
  private JsonNode command(String name, String id) { var n = json.createObjectNode(); n.put("type", name); n.put("id", id); return n; }
  private JsonNode prompt(String task, String id) { var n = json.createObjectNode(); n.put("type", "prompt"); n.put("id", id); n.put("message", task); return n; }
  private static boolean hasEvent(List<JsonNode> events, String type) {
    return events.stream().anyMatch(e -> type.equals(e.path("type").asString("")));
  }

  private static List<JsonNode> snapshot(List<JsonNode> events, Object monitor) {
    synchronized (monitor) {
      return List.copyOf(events);
    }
  }
  private static void awaitSuccessfulResponse(List<JsonNode> events, Object monitor, String id,
                                              String command, Duration timeout,
                                              AtomicReference<RuntimeException> failure) {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (events.stream().noneMatch(e -> id.equals(e.path("id").asString("")))
          && failure.get() == null
          && System.nanoTime() < deadline) {
        try { TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, deadline - System.nanoTime())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
      }
    }
    if (failure.get() != null) throw failure.get();
    List<JsonNode> snapshot;
    synchronized (monitor) {
      snapshot = List.copyOf(events);
    }
    JsonNode response = snapshot.stream()
        .filter(e -> id.equals(e.path("id").asString("")))
        .findFirst().orElseThrow(() ->
            new IllegalStateException("Pi " + command + " response timed out; exchange="
                + boundedTail(snapshot, 2048)));
    if (response.path("success").isBoolean() && !response.path("success").asBoolean()) {
      throw new IllegalStateException("Pi " + command + " RPC failed: "
          + response.path("error").asString("unknown error"));
    }
  }

  private static String boundedTail(List<JsonNode> events, int maxChars) {
    String text = events.toString();
    return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
  }

  private static void validateRequestedModel(List<JsonNode> events, String stateId,
                                             List<String> argv) {
    JsonNode response = events.stream().filter(event -> stateId.equals(event.path("id").asString("")))
        .findFirst().orElse(null);
    JsonNode model = response == null ? null : response.path("data").path("model");
    if (model == null || !model.isObject()) {
      return;
    }
    String requestedProvider = option(argv, "--provider");
    String requestedModel = option(argv, "--model");
    String actualProvider = model.path("provider").asString("");
    String actualModel = model.path("id").asString("");
    if ((!requestedProvider.isBlank() && !actualProvider.isBlank()
        && !requestedProvider.equals(actualProvider))
        || (!requestedModel.isBlank() && !actualModel.isBlank()
        && !requestedModel.equals(actualModel))) {
      throw new IllegalStateException("Pi model mismatch: requested " + requestedProvider + "/"
          + requestedModel + ", runtime returned " + actualProvider + "/" + actualModel);
    }
  }

  private static String option(List<String> argv, String name) {
    for (int i = 0; i + 1 < argv.size(); i++) {
      if (name.equals(argv.get(i))) {
        return argv.get(i + 1);
      }
    }
    return "";
  }

  public record CodingAttempt(int processExitCode, boolean settled, String rawExchange,
                              List<JsonNode> events, long stdoutBytes, long stderrBytes) {
    public CodingAttempt {
      rawExchange = rawExchange == null ? "" : rawExchange;
      events = events == null ? List.of() : List.copyOf(events);
    }
  }
}
