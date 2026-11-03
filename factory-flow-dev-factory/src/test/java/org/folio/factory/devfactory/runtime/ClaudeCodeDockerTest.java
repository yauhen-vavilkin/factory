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
    private static final String IMAGE = "factory-dev-claude-code:2.1.287-java21-node22";
    private static final String NODE_IMAGE = "node:22.22.0-bookworm";
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
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
            mock.model = model;
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 60);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(outcome.metrics()).containsEntry("model", model).doesNotContainKey("usageIncomplete");
            assertThat(mock.calls).hasSize(2).allSatisfy(call ->
                    assertThat(call.body.path("model").asString()).isEqualTo(model));
        }
    }

    /** The agent opens a large staged image: Claude Code repeats it as one oversized base64 frame, which must not fail the attempt. */
    @Test void aLargeStagedImageTheAgentOpensDoesNotBreakTheEventStream() throws Exception {
        var picture = new java.awt.image.BufferedImage(700, 700, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(11);
        for (int x = 0; x < 700; x++) for (int y = 0; y < 700; y++) picture.setRGB(x, y, random.nextInt(1 << 24));
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", png);
        var jira = org.mockito.Mockito.mock(org.folio.factory.connectors.jira.JiraConnector.class);
        org.mockito.Mockito.when(jira.downloadAttachment(org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(png.toByteArray());
        var request = new CodingRequest("TASK-1", "Implement task", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)), null,
                List.of(new CodingRequest.Attachment("1", "noise.png", "image/png", png.size(), "", "")));
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-staged-image");
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
            mock.tool = "Read";
            mock.toolInput = Map.of("file_path", "/tmp/factory-attachments/img-1.png");
            var base = config(mock, "api-key");
            var withImages = new DevRuntimeProperties.ClaudeCode(base.image(), base.api(), base.baseUrl(), base.model(),
                    base.authentication(), base.apiKey(), base.authToken(), base.maxTurns(), base.maxOutputTokens(), null, true);
            var outcome = new ImageStagingRuntime(new ClaudeCodeCodingRuntime(withImages), jira, new DevAttachmentProperties())
                    .code(workload, request, 90);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(mock.calls).hasSize(2);
            assertThat(mock.calls.get(1).body.toString()).contains("\"type\":\"image\"").contains("base64");
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
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
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
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
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
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
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

    @Test void yarnImageRunsTheRealCliWithNodeAndYarnAndExportsSourcesOnly() throws Exception {
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-yarn-image");
             var workload = scope.createSeeded(IMAGE, source, yarnEnvironment(), "coding")) {
            mock.command = "node --version && yarn --version && mkdir node_modules && " + mock.command;
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request(), 90);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(mock.calls).hasSize(2);
            assertThat(mock.calls.getLast().body.path("messages").toString()).contains("v22.").contains("1.22");
            workload.stop();
            workload.export(exported);
            assertThat(Files.readString(exported.resolve("changed.txt"))).isEqualTo("edited by Claude\n");
            assertThat(exported.resolve("node_modules")).doesNotExist();
        }
    }

    /** Trusted baseline, a real coding session in the yarn image, then verification of the result in a fresh workload. */
    @Test void yarnFixtureGoesFromBaselineThroughCodingToPassingJestVerification() throws Exception {
        Files.writeString(source.resolve("package.json"), """
                {"name":"fixture","version":"1.0.0","private":true,"scripts":{"test":"jest"},
                 "devDependencies":{"jest":"29.7.0","jest-junit":"16.0.0"}}
                """);
        Files.writeString(source.resolve("jest.config.js"),
                "module.exports = { testEnvironment: 'node', reporters: ['default', 'jest-junit'] };\n");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/add.js"), "exports.add = (a, b) => a + b;\n");
        Files.writeString(source.resolve("src/add.test.js"),
                "const { add } = require('./add');\ntest('add', () => expect(add(1, 2)).toBe(3));\n");
        // The fixture has no lockfile: the starting build resolves and pins one, every later stage installs from it.
        var runtime = new DevRuntimeProperties(Map.of("jest", List.of("yarn", "test")), null, 600, null, Map.of(),
                Map.of("jest", Toolchain.YARN), new DevRuntimeProperties.Yarn(volume,
                        DevRuntimeProperties.Yarn.DEFAULT_FOLIO_REGISTRY));
        var environment = runtime.environment("jest");
        var plan = runtime.verificationCommand("jest");
        PinnedLockfile pinned;

        try (var scope = new DockerWorkloads().beginStep(executionId, "yarn-baseline");
             var baseline = scope.createTrusted(NODE_IMAGE, source, environment)) {
            var result = baseline.execute(runtime.verificationCommand("jest", InstallMode.RESOLVE), 600);
            assertThat(result.exitCode()).as(result.diagnostics()).isZero();
            pinned = baseline.readLockfile().orElseThrow();
        }
        try (var mock = new MessagesEndpoint(false, false);
             var scope = new DockerWorkloads().beginStep(executionId, "yarn-code");
             var workload = scope.createSeeded(IMAGE, source, environment, "coding")) {
            // Factory prepares the workspace before the agent starts, so the agent finds it ready.
            workload.seedLockfile(pinned);
            var prepared = PreparedWorkspace.prepare(workload, runtime, "jest", event -> { });
            assertThat(prepared.failure()).isNull();
            assertThat(prepared.installed()).isTrue();
            assertThat(workload.execute(List.of("test", "-d", "node_modules/jest"), 30).exitCode()).isZero();
            mock.command = "printf 'exports.sub = (a, b) => a - b;\\n' > src/sub.js "
                    + "&& printf \"const { sub } = require('./sub');\\ntest('sub', () => expect(sub(3, 1)).toBe(2));\\n\" "
                    + "> src/sub.test.js && yarn test 2>&1 | tail -5";
            var outcome = new ClaudeCodeCodingRuntime(config(mock, "api-key"))
                    .code(workload, ClaudeCodeCodingRuntimeTest.request().withVerification("yarn test", true), 300);
            assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            assertThat(mock.calls.getFirst().body.toString()).contains("by running: yarn test")
                    .contains("already installed in /workspace");
            assertThat(mock.calls.getLast().body.path("messages").toString()).contains("Tests:");
            workload.stop();
            workload.export(exported);
        }
        assertThat(exported.resolve("src/sub.js")).exists();
        assertThat(exported.resolve("node_modules")).doesNotExist();

        Path candidate = Files.createTempDirectory("factory-yarn-candidate-");
        copyTree(source, candidate);
        copyTree(exported, candidate);
        try (var scope = new DockerWorkloads().beginStep(executionId, "yarn-verify");
             var verification = scope.createSeeded(NODE_IMAGE, candidate, environment, "verification")) {
            verification.seedLockfile(pinned);
            assertThat(verification.execute(List.of("rm", "-rf", "artifacts/jest-junit"), 60).exitCode()).isZero();
            var result = verification.execute(plan, 600);
            assertThat(result.exitCode()).as(result.diagnostics()).isZero();
            var report = verification.execute(List.of("cat", "artifacts/jest-junit/junit.xml"), 30);
            assertThat(report.output()).contains("<testsuites").contains("tests=\"2\"").contains("failures=\"0\"");
        }
    }

    private BuildEnvironment yarnEnvironment() {
        return new BuildEnvironment(Toolchain.YARN, volume, DevRuntimeProperties.Yarn.DEFAULT_FOLIO_REGISTRY);
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test void providerFailureKeepsPartialEditsAvailableForTheFeedbackOrchestrator() throws Exception {
        try (var mock = new MessagesEndpoint(true, false);
             var scope = new DockerWorkloads().beginStep(executionId, "claude-failure");
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
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
                 var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
                var outcome = runtime.code(workload, request, 60);
                assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
                assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS);
            }
            try (var scope = new DockerWorkloads().beginStep(executionId, "continue");
                 var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
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
        private volatile String tool = "Bash";
        private volatile Map<String, Object> toolInput = null;
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
                    byte[] response = stream(calls.size(), edit, answer, model, tool,
                            toolInput != null ? toolInput : Map.of("command", command, "description", "Edit fixture"),
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

    private static String stream(int id, boolean edit, String answer, String model, String tool,
                                 Map<String, Object> toolInput, boolean includeInputUsage, boolean includeOutputUsage) {
        var events = new ArrayList<Map<String, Object>>();
        var message = new LinkedHashMap<String, Object>(Map.of("id", "msg_" + id, "type", "message",
                "role", "assistant", "model", model, "content", List.of()));
        if (includeInputUsage) message.put("usage", Map.of("input_tokens", 40, "output_tokens", 0,
                "cache_read_input_tokens", 3, "cache_creation_input_tokens", 2));
        events.add(Map.of("type", "message_start", "message", message));
        Object block = edit ? Map.of("type", "tool_use", "id", "tool_" + id, "name", tool, "input", Map.of())
                : Map.of("type", "text", "text", "");
        events.add(Map.of("type", "content_block_start", "index", 0, "content_block", block));
        Object delta = edit ? Map.of("type", "input_json_delta", "partial_json", JSON.writeValueAsString(toolInput))
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
