package org.folio.factory.devfactory.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/** Real pinned CLI, synthetic credentials and a local Messages server only; no provider spend. */
@EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
class ClaudeCodeDockerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String IMAGE = "factory-dev-claude-code:2.1.287-java21";
    private static final String MODEL = "operator/Custom-Model-2026";
    private final UUID executionId = UUID.randomUUID();
    private final String volume = "factory-claude-test-" + executionId;
    @TempDir Path source;
    @TempDir Path exported;

    @AfterEach void cleanup() {
        assertThat(Processes.run(null, List.of("docker", "ps", "-aq", "--filter",
                "label=factory.dev-execution=" + executionId), 30).requireSuccess().output()).isBlank();
        Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
    }

    @ParameterizedTest @ValueSource(strings = {"sonnet", "opus", "haiku", "fable", "best", "default", "opusplan",
            "sonnet[1m]", "custom/Model[1m]", "claude-opus-4-1", "operator/Model'quoted\""})
    void cliAliasesAndContextSuffixesRemainLiteralOperatorModelIds(String model) throws Exception {
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-literal-model");
             var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
            mock.model = model;
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 60);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(outcome.metrics()).containsEntry("model", model).doesNotContainKey("usageIncomplete");
            assertThat(mock.calls).hasSize(2).allSatisfy(call ->
                    assertThat(call.body.path("model").asString()).isEqualTo(model));
        }
    }

    @Test void mavenRunsWithoutCallingTheAutoPermissionClassifier() throws Exception {
        Files.writeString(source.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
                  <artifactId>permissions</artifactId><version>1</version><packaging>pom</packaging>
                </project>
                """);
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-maven-permissions");
             var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
            mock.command = "mvn -B -ntp -o validate && " + mock.command;
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 90);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(outcome.metrics()).containsEntry("inputTokens", 80L).containsEntry("outputTokens", 10L)
                    .doesNotContainKey("usageIncomplete");
            assertThat(mock.calls).hasSize(2).allSatisfy(call ->
                    assertThat(call.body.path("model").asString()).isEqualTo(MODEL));
            assertThat(mock.calls.getLast().body.path("messages").toString()).contains("BUILD SUCCESS");
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("changed.txt"))).isEqualTo("edited by Claude\n");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"all", "first", "last", "first-output", "last-input"})
    void omittedProviderUsageStaysIncompleteEvenWhenOtherTurnsReportTokens(String omitted) throws Exception {
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-missing-usage");
             var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
            mock.missingInputUsage = switch (omitted) {
                case "first" -> Set.of(1);
                case "last", "last-input" -> Set.of(2);
                case "first-output" -> Set.of();
                default -> Set.of(1, 2);
            };
            mock.missingOutputUsage = switch (omitted) {
                case "first", "first-output" -> Set.of(1);
                case "last" -> Set.of(2);
                case "last-input" -> Set.of();
                default -> Set.of(1, 2);
            };
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 60);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(outcome.metrics()).containsEntry("usageIncomplete", true)
                    .containsEntry("inputTokens", 40L * (2 - mock.missingInputUsage.size()))
                    .containsEntry("outputTokens", 5L * (2 - mock.missingOutputUsage.size()));
            assertThat(mock.calls).hasSize(2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"api-key", "bearer-token"})
    void realCliEditsExportsAndReportsUsageWithExactModelAndSelectedAuthentication(String authentication) throws Exception {
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-contract");
             var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
            var runtime = new ClaudeCodeCodingRuntime(config(mock, authentication));
            var events = new CopyOnWriteArrayList<Map<String, Object>>();
            assertThat(workload.execute(List.of("sh", "-c", "test \"$HOME\" = /tmp/factory-home && "
                    + "test ! -e /var/run/docker.sock && test -z \"$ANTHROPIC_API_KEY$ANTHROPIC_AUTH_TOKEN\" && "
                    + "java -version && mvn -version"), 30).exitCode()).isZero();
            var outcome = runtime.code(workload, ClaudeCodeCodingRuntimeTest.request(), 90, events::add);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(outcome.summary()).contains("Implemented");
            assertThat(outcome.metrics()).containsEntry("inputTokens", 80L).containsEntry("outputTokens", 10L)
                    .containsEntry("cacheReadTokens", 6L).containsEntry("cacheWriteTokens", 4L)
                    .doesNotContainKey("usageIncomplete");
            assertThat(events).anySatisfy(e -> assertThat(e).containsEntry("activity", "tool_execution_start"));
            assertThat(events.toString()).doesNotContain("mock-key", "mock-token");
            assertThat(workload.execute(List.of("test", "!", "-e", "/tmp/factory-claude"), 30).exitCode()).isZero();
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("changed.txt"))).isEqualTo("edited by Claude\n");
            assertThat(Files.exists(exported.resolve(".claude"))).isFalse();
            assertThat(mock.calls.size()).isEqualTo(2);
            for (var call : mock.calls) {
                assertThat(call.body.path("model").asString()).isEqualTo(MODEL);
                assertThat(call.body.path("max_tokens").asInt()).isEqualTo(4096);
                assertThat(call.body.path("stream").asBoolean()).isTrue();
                assertThat(call.path).isEqualTo("/gateway/v1/messages");
                assertThat(call.apiKey).isEqualTo(authentication.equals("api-key") ? "mock-key" : null);
                assertThat(call.authorization).isEqualTo(authentication.equals("bearer-token") ? "Bearer mock-token" : null);
            }
        }
    }

    @Test void providerFailureKeepsPartialEditsAvailableForTheFeedbackOrchestrator() throws Exception {
        try (var mock = new MessagesEndpoint(true, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-failure");
             var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 60);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.FAILED);
            assertThat(outcome.toString()).doesNotContain("mock-key", "private provider diagnostic");
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("changed.txt"))).isEqualTo("edited by Claude\n");
        }
    }

    @Test void decisionAndConfirmedContinuationUseFreshSessions() throws Exception {
        try (var mock = new MessagesEndpoint(false, true)) {
            var runtime = new ClaudeCodeCodingRuntime(config(mock, "bearer-token"));
            var request = ClaudeCodeCodingRuntimeTest.request();
            try (var scope = new DockerWorkloads().beginStep(executionId, "clarify");
                 var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
                var outcome = runtime.code(workload, request, 60);
                assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
                assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS);
            }
            try (var scope = new DockerWorkloads().beginStep(executionId, "continue");
                 var workload = scope.createSeeded(IMAGE, source, volume, "coding")) {
                var outcome = runtime.code(workload, request.withDecision(new CodingRequest.ConfirmedDecision(
                        "decision-1", "PRODUCT_REQUIREMENTS", "Which API?", "Use public API")), 60);
                assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
                assertThat(mock.calls.getLast().body.toString()).contains("Use public API");
            }
        }
    }

    @Test void noninteractivePermissionsAlsoWorkAsTheComposeRootUid() throws Exception {
        try (var mock = new MessagesEndpoint(false, false)) {
            mock.command = "mvn -B -ntp --version && " + mock.command;
            String name = "factory-claude-root-" + executionId;
            try {
                // Synthetic credentials only. Actual adapter credentials are never passed on argv.
                var command = List.of("docker", "run", "--rm", "--name", name,
                        "--label", "factory.dev-execution=" + executionId,
                        "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", "0:0",
                        "--env", "HOME=/tmp/factory-home", "--env", "ANTHROPIC_AUTH_TOKEN=mock-token",
                        "--env", "ANTHROPIC_BASE_URL=" + mock.url(), "--entrypoint", "claude", IMAGE,
                        "--bare", "--print", "--output-format", "stream-json", "--verbose", "--no-session-persistence",
                        "--tools", "Bash,Read,Edit,Write,Glob,Grep", "--allowedTools", "Bash,Read,Edit,Write,Glob,Grep",
                        "--permission-mode", "dontAsk", "--permission-prompts", "none",
                        "--model", MODEL, "--max-turns", "3", "Use public API");
                var result = Processes.run(null, command, 60);
                assertThat(result.exitCode()).as(result.diagnostics()).isZero();
                var protocol = new ClaudeCodeProtocol(MODEL, s -> s, e -> {});
                result.output().lines().forEach(protocol);
                assertThat(protocol.outcome(0).status()).isEqualTo(CodingOutcome.Status.COMPLETED);
                assertThat(mock.calls.size()).isEqualTo(2); // Includes an automatically approved Bash edit.
                assertThat(mock.calls.getLast().body.path("messages").toString()).contains("Apache Maven");
            } finally { Processes.run(null, List.of("docker", "rm", "--force", name), 30); }
        }
    }

    private static DevRuntimeProperties.ClaudeCode config(MessagesEndpoint mock, String authentication) {
        return new DevRuntimeProperties.ClaudeCode(IMAGE, "anthropic-messages", mock.url(), mock.model,
                authentication, "mock-key", "mock-token", 4, 4096, null);
    }

    private static final class MessagesEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private volatile String model = MODEL;
        private volatile String command = "printf 'edited by Claude\\n' > /workspace/changed.txt";
        private volatile Set<Integer> missingInputUsage = Set.of();
        private volatile Set<Integer> missingOutputUsage = Set.of();
        MessagesEndpoint(boolean failAfterEdit, boolean decision) throws Exception {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (exchange.getRequestMethod().equals("HEAD")) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    var body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    calls.add(new Call(exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("x-api-key"),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                    boolean toolResult = body.path("messages").toString().contains("tool_result");
                    if (failAfterEdit && toolResult) {
                        byte[] error = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"private provider diagnostic mock-key\"}}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(400, error.length);
                        exchange.getResponseBody().write(error);
                        return;
                    }
                    boolean edit = !decision && !toolResult;
                    String answer = decision && !body.toString().contains("Use public API")
                            ? "FACTORY_DECISION_REQUIRED: {\"kind\":\"PRODUCT_REQUIREMENTS\",\"question\":\"Which API?\",\"options\":[],\"evidence\":\"Two APIs\"}"
                            : "Implemented";
                    byte[] response = stream(calls.size(), edit, answer, model, command,
                            !missingInputUsage.contains(calls.size()), !missingOutputUsage.contains(calls.size()))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }
        String url() { return "http://host.docker.internal:" + server.getAddress().getPort() + "/gateway"; }
        @Override public void close() { server.stop(0); }
    }

    private record Call(String path, String apiKey, String authorization, JsonNode body) {
        @Override public String toString() { return "Call[path=" + path + "]"; }
    }

    private static String stream(int id, boolean edit, String answer, String model, String command,
                                 boolean includeInputUsage, boolean includeOutputUsage) {
        var events = new ArrayList<Map<String, Object>>();
        var message = new LinkedHashMap<String, Object>(Map.of("id", "msg_" + id, "type", "message",
                "role", "assistant", "model", model, "content", List.of()));
        if (includeInputUsage) message.put("usage", Map.of("input_tokens", 40, "output_tokens", 0,
                "cache_read_input_tokens", 3, "cache_creation_input_tokens", 2));
        events.add(Map.of("type", "message_start", "message", message));
        Object block = edit ? Map.of("type", "tool_use", "id", "tool_" + id, "name", "Bash", "input", Map.of())
                : Map.of("type", "text", "text", "");
        events.add(Map.of("type", "content_block_start", "index", 0, "content_block", block));
        Object delta = edit ? Map.of("type", "input_json_delta", "partial_json", JSON.writeValueAsString(
                Map.of("command", command, "description", "Edit fixture")))
                : Map.of("type", "text_delta", "text", answer);
        events.add(Map.of("type", "content_block_delta", "index", 0, "delta", delta));
        events.add(Map.of("type", "content_block_stop", "index", 0));
        var finalDelta = new LinkedHashMap<String, Object>(Map.of("type", "message_delta",
                "delta", Map.of("stop_reason", edit ? "tool_use" : "end_turn")));
        if (includeOutputUsage) finalDelta.put("usage", Map.of("output_tokens", 5));
        events.add(finalDelta);
        events.add(Map.of("type", "message_stop"));
        return events.stream().map(e -> "event: " + e.get("type") + "\ndata: " + JSON.writeValueAsString(e) + "\n\n")
                .collect(java.util.stream.Collectors.joining());
    }
}
