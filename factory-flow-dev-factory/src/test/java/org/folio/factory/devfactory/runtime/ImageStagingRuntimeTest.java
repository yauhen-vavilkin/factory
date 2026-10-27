package org.folio.factory.devfactory.runtime;

import org.folio.factory.connectors.jira.JiraConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageStagingRuntimeTest {
    private static final byte[] PNG = png();
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3};

    private final JiraConnector jira = mock(JiraConnector.class);
    private final CodingRuntime delegate = mock(CodingRuntime.class);
    private final DockerWorkloads.Workload workload = mock(DockerWorkloads.Workload.class);
    private final AtomicReference<CodingRequest> received = new AtomicReference<>();
    private final Map<String, byte[]> copied = new java.util.TreeMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    private static byte[] png() {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13});
        out.writeBytes("IHDR".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[17]);
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes("IDAT".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @BeforeEach
    void wire() {
        when(delegate.acceptsImages()).thenReturn(true);
        when(delegate.code(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            received.set(invocation.getArgument(1));
            return CodingOutcome.completed("Done", Map.of());
        });
        when(workload.execute(anyList(), anyInt())).thenReturn(new Processes.Result(0, ""));
        doAnswer(invocation -> {
            Path directory = Path.of((String) invocation.getArgument(0));
            try (var files = Files.list(directory)) {
                for (Path file : (Iterable<Path>) files::iterator) copied.put(file.getFileName().toString(), Files.readAllBytes(file));
            }
            return null;
        }).when(workload).copy(anyString(), eq(ImageStagingRuntime.DIRECTORY));
    }

    private ImageStagingRuntime runtime(int maxImages, long maxBytes) {
        return new ImageStagingRuntime(delegate, jira, new DevAttachmentProperties(maxImages, maxBytes));
    }

    private static CodingRequest.Attachment attachment(String id, String name, long size) {
        return new CodingRequest.Attachment(id, name, "image/png", size, "", "");
    }

    private static CodingRequest request(CodingRequest.Attachment... attachments) {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults(), List.of(attachments));
    }

    private CodingOutcome run(ImageStagingRuntime runtime, CodingRequest request) {
        return runtime.code(workload, request, 60, events::add);
    }

    @Test
    void copiesEachImageIntoTheWorkloadAndTellsTheRuntimeWhereItIs() {
        when(jira.downloadAttachment("1", 1000)).thenReturn(PNG);
        when(jira.downloadAttachment("2", 1000)).thenReturn(JPEG);

        run(runtime(0, 1000), request(attachment("1", "a.png", 10), attachment("2", "b.jpg", 10)));

        assertThat(received.get().attachments()).extracting(CodingRequest.Attachment::path)
                .containsExactly("/tmp/factory-attachments/img-1.png", "/tmp/factory-attachments/img-2.jpg");
        assertThat(copied).containsOnlyKeys("img-1.png", "img-2.jpg");
        assertThat(copied.get("img-1.png")).isEqualTo(PNG);
        verify(workload).execute(List.of("mkdir", "-p", "/tmp/factory-attachments"), 30);
    }

    @Test
    void auditsNamesSizesAndHashesButNeverTheContent() {
        when(jira.downloadAttachment("1", 1000)).thenReturn(PNG);

        run(runtime(0, 1000), request(attachment("1", "a.png", 10)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("activity", "attachments_staged");
        assertThat(events.get(0).get("images").toString())
                .contains("filename=a.png", "bytes=" + PNG.length).containsPattern("sha256=[0-9a-f]{64}");
    }

    @Test
    void anImageThatCannotBeProvidedIsRecordedWithTheReasonAndDoesNotStopTheAttempt() {
        when(jira.downloadAttachment("1", 1000)).thenThrow(new IllegalStateException("HTTP 404"));
        when(jira.downloadAttachment("2", 1000)).thenReturn("<html>login page</html>".getBytes(StandardCharsets.UTF_8));
        when(jira.downloadAttachment("3", 1000)).thenReturn(PNG);

        var outcome = run(runtime(0, 1000), request(attachment("1", "gone.png", 10), attachment("2", "fake.png", 10),
                attachment("3", "ok.png", 10), attachment("4", "huge.png", 5000)));

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        var sent = received.get().attachments();
        assertThat(sent.get(0).path()).isEmpty();
        assertThat(sent.get(0).note()).contains("could not be downloaded");
        assertThat(sent.get(1).note()).contains("not a png, jpeg, gif or webp image");
        assertThat(sent.get(2).path()).isEqualTo("/tmp/factory-attachments/img-1.png");
        assertThat(sent.get(3).note()).contains("larger than 1000 bytes");
        verify(jira, never()).downloadAttachment(eq("4"), anyLong());
        assertThat(copied).containsOnlyKeys("img-1.png");
    }

    @Test
    void honoursTheImageCountLimitWhenOneIsSet() {
        when(jira.downloadAttachment(anyString(), anyLong())).thenReturn(PNG);

        run(runtime(2, 1000), request(attachment("1", "a.png", 1), attachment("2", "b.png", 1), attachment("3", "c.png", 1)));

        assertThat(received.get().attachments()).extracting(a -> !a.path().isBlank()).containsExactly(true, true, false);
        assertThat(received.get().attachments().get(2).note()).contains("only the first 2 images");
        verify(jira, never()).downloadAttachment(eq("3"), anyLong());
    }

    @Test
    void aRuntimeThatCannotReadImagesDownloadsNothingAndSaysSo() {
        when(delegate.acceptsImages()).thenReturn(false);

        run(runtime(0, 1000), request(attachment("1", "a.png", 1)));

        verifyNoInteractions(jira);
        verify(workload, never()).copy(anyString(), anyString());
        assertThat(received.get().attachments().get(0).note()).contains("not set up to receive images");
        assertThat(received.get().attachments().get(0).path()).isEmpty();
    }

    @Test
    void aRequestWithoutAttachmentsIsPassedThroughUntouched() {
        var request = request();

        run(runtime(0, 1000), request);

        assertThat(received.get()).isSameAs(request);
        verifyNoInteractions(jira);
        verifyNoInteractions(workload);
        assertThat(events).isEmpty();
    }

    @Test
    void whenTheCopyFailsTheImagesAreReportedAsNotProvided() {
        when(jira.downloadAttachment("1", 1000)).thenReturn(PNG);
        doAnswer(invocation -> { throw new IllegalStateException("docker cp failed"); })
                .when(workload).copy(anyString(), eq(ImageStagingRuntime.DIRECTORY));

        var outcome = run(runtime(0, 1000), request(attachment("1", "a.png", 1)));

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(received.get().attachments().get(0).path()).isEmpty();
        assertThat(received.get().attachments().get(0).note()).contains("could not be copied");
    }

    @Test
    void bothRuntimesAcceptImagesOnlyWhenTheOperatorEnablesThem() {
        var claude = new DevRuntimeProperties.ClaudeCode(null, null, null, null, null, null, null, null, null, null);

        assertThat(new ClaudeCodeCodingRuntime(claude).acceptsImages()).isFalse();
        assertThat(new PiCodingRuntime(new DevRuntimeProperties.Coding("i", "p", "m", null, null, "k", "pi", null, null))
                .acceptsImages()).isFalse();
    }

    @Test
    void delegatesTheRuntimeIdentity() {
        when(delegate.id()).thenReturn("pi");
        when(delegate.image()).thenReturn("img");
        when(delegate.redact("x")).thenReturn("y");

        var runtime = runtime(0, 1000);

        assertThat(runtime.id()).isEqualTo("pi");
        assertThat(runtime.image()).isEqualTo("img");
        assertThat(runtime.redact("x")).isEqualTo("y");
        assertThat(runtime.acceptsImages()).isTrue();
    }
}
