package org.folio.factory.devfactory.runtime;

import org.folio.factory.connectors.jira.JiraConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The staged image really arrives in a coding workload, readable by the agent and outside the exported workspace. */
@EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
class ImageStagingDockerTest {
    private static final String IMAGE = "factory-dev-pi:0.85.1-java21-node22";
    private final UUID executionId = UUID.randomUUID();
    private final String volume = "factory-image-test-" + executionId;
    @TempDir Path source;

    @AfterEach void cleanup() {
        Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
    }

    @Test void theAgentCanReadTheImageThatWasStagedAndItIsNotPartOfTheWorkspace() throws Exception {
        byte[] png = new byte[2048];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13}, 0, png, 0, 12);
        System.arraycopy("IHDR".getBytes(StandardCharsets.US_ASCII), 0, png, 12, 4);
        System.arraycopy("IDAT".getBytes(StandardCharsets.US_ASCII), 0, png, 37, 4);
        for (int i = 100; i < png.length; i++) png[i] = (byte) i;
        var jira = mock(JiraConnector.class);
        when(jira.downloadAttachment(eq("1"), anyLong())).thenReturn(png);
        var seen = new AtomicReference<String>();
        var delegate = new CodingRuntime() {
            @Override public String id() { return "probe"; }
            @Override public String image() { return IMAGE; }
            @Override public void requireConfigured() { }
            @Override public boolean acceptsImages() { return true; }
            @Override public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds) {
                var path = request.attachments().get(0).path();
                var listing = workload.execute(List.of("sh", "-c", "id -u; ls -l " + path
                        + "; sha256sum " + path + "; ls /workspace | grep -c img- || true"), 30);
                seen.set(listing.output());
                return CodingOutcome.completed("probe", Map.of());
            }
        };
        var request = new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults(),
                List.of(new CodingRequest.Attachment("1", "shot.png", "image/png", png.length, "", "")));

        try (var scope = new DockerWorkloads().beginStep(executionId, "image-staging");
             var workload = scope.createSeeded(IMAGE, source, BuildEnvironment.maven(volume), "coding")) {
            var outcome = new ImageStagingRuntime(delegate, jira, new DevAttachmentProperties())
                    .code(workload, request, 60, (Consumer<Map<String, Object>>) event -> { });
            assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        }

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        assertThat(seen.get()).contains("/tmp/factory-attachments/img-1.png").contains(expected).endsWith("0\n");
    }
}
