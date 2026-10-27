package org.folio.factory.devfactory.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Run with -Dfactory.pi.contract.image=factory-dev-pi:0.85.1-java21-node22 after building the coding image. */
class PiCodingHttpContractTest {
    @TempDir Path workspace;

    @Test void codemieProfileSendsTheExpectedChatCompletionsRequest() throws Exception {
        String image = System.getProperty("factory.pi.contract.image", "");
        assumeTrue(!image.isBlank(), "Set factory.pi.contract.image to run the Pi HTTP contract test");
        contract(image, BuildEnvironment.maven("factory-dev-m2-cache"));
    }

    @Test void sameImageSendsTheSameRequestInAYarnWorkloadWithNodeAndYarn() throws Exception {
        String image = System.getProperty("factory.pi.contract.image", "");
        assumeTrue(!image.isBlank(), "Set factory.pi.contract.image to run the Pi HTTP contract test");
        contract(image, new BuildEnvironment(Toolchain.YARN, "factory-dev-yarn-cache",
                DevRuntimeProperties.Yarn.DEFAULT_FOLIO_REGISTRY));
    }

    /** A large image makes Pi emit frames over the per-frame limit; the attempt must still complete and the model must get the image. */
    @Test void aLargeTicketImageReachesTheModelAndDoesNotBreakTheEventStream() throws Exception {
        String image = System.getProperty("factory.pi.contract.image", "");
        assumeTrue(!image.isBlank(), "Set factory.pi.contract.image to run the Pi HTTP contract test");
        var picture = new java.awt.image.BufferedImage(700, 700, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(7);
        for (int x = 0; x < 700; x++) for (int y = 0; y < 700; y++) picture.setRGB(x, y, random.nextInt(1 << 24));
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", png);
        assertThat(png.size()).as("test image must exceed the 512 KiB frame limit once base64-encoded").isGreaterThan(400 * 1024);
        var jira = org.mockito.Mockito.mock(org.folio.factory.connectors.jira.JiraConnector.class);
        org.mockito.Mockito.when(jira.downloadAttachment(org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(png.toByteArray());
        var captured = new AtomicReference<RecordedRequest>();
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                captured.set(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] response = ("""
                        data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"gemini-3.8-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Done"},"finish_reason":null}]}

                        data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"gemini-3.8-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var pi = new DevRuntimeProperties.Pi(null, null, null, null, null, null, null, null, null, true);
            var config = new DevRuntimeProperties.Coding(image, "codemie", "gemini-3.8-flash",
                    "http://host.docker.internal:" + server.getAddress().getPort() + "/v1",
                    "openai-completions", "contract-test-key", "pi", "high", 65536, pi, null);
            var request = new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(),
                    List.of(), List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main",
                    "a".repeat(40)), CodingRequest.Constraints.defaults(),
                    List.of(new CodingRequest.Attachment("1", "noise.png", "image/png", png.size(), "", "")));
            var runtime = new ImageStagingRuntime(new PiCodingRuntime(config), jira, new DevAttachmentProperties());
            try (var scope = new DockerWorkloads().beginStep(java.util.UUID.randomUUID(), "coding-image-contract");
                 var workload = scope.createSeeded(image, workspace, BuildEnvironment.maven("factory-dev-m2-cache"), "coding")) {
                var outcome = runtime.code(workload, request, 120);
                assertThat(outcome.status()).as(outcome.toString()).isEqualTo(CodingOutcome.Status.COMPLETED);
            }
            var body = JsonMapper.builder().build().readTree(captured.get().body());
            assertThat(captured.get().body()).contains("\"image_url\"").contains("data:image/png;base64,");
            assertThat(body.path("messages").toString()).contains("/tmp/factory-attachments/img-1.png");
        } finally {
            server.stop(0);
        }
    }

    private void contract(String image, BuildEnvironment environment) throws Exception {
        var captured = new AtomicReference<RecordedRequest>();
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                captured.set(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] response = ("""
                        data: {"id":"contract","object":"chat.completion.chunk","created":1,"model":"gemini-3.8-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Done"},"finish_reason":null}]}

                        data: {"id":"contract","object":"chat.completion.chunk","created":1,"model":"gemini-3.8-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"id":"contract","object":"chat.completion.chunk","created":1,"model":"gemini-3.8-flash","choices":[],"usage":{"prompt_tokens":40,"completion_tokens":5,"total_tokens":45}}

                        data: [DONE]

                        """).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var config = new DevRuntimeProperties.Coding(image, "codemie", "gemini-3.8-flash",
                    "http://host.docker.internal:" + server.getAddress().getPort() + "/v1",
                    "openai-completions", "contract-test-key", "pi", "high", 65536);
            var request = new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(),
                    List.of(), List.of(), new CodingRequest.RepositoryTarget("repo", "owner/repo", "main",
                    "a".repeat(40)), CodingRequest.Constraints.defaults());
            try (var scope = new DockerWorkloads().beginStep(java.util.UUID.randomUUID(), "coding-contract");
                 var workload = scope.createSeeded(image, workspace, environment, "coding")) {
                if (environment.toolchain() == Toolchain.YARN)
                    assertThat(workload.execute(List.of("sh", "-c", "node --version | grep -q '^v22\\.' "
                            + "&& yarn --version | grep -q '^1\\.' && test \"$CI\" = true"), 30).exitCode()).isZero();
                assertThat(new PiCodingRuntime(config).code(workload, request, 90).status())
                        .isEqualTo(CodingOutcome.Status.COMPLETED);
            }
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().method()).isEqualTo("POST");
            assertThat(captured.get().path()).isEqualTo("/v1/chat/completions");
            assertThat(captured.get().authorization()).isEqualTo("Bearer contract-test-key");
            var body = JsonMapper.builder().build().readTree(captured.get().body());
            assertThat(body.path("model").asString()).isEqualTo("gemini-3.8-flash");
            assertThat(body.path("reasoning_effort").asString()).isEqualTo("high");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(65536);
            assertThat(body.path("stream").asBoolean()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private record RecordedRequest(String method, String path, String authorization, String body) { }
}
