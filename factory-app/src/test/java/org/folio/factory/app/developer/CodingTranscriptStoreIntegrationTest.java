package org.folio.factory.app.developer;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Batches reach the database while a run is going, write failures leave a note, retention removes the rows. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class CodingTranscriptStoreIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CodingTranscriptStore store;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void fullBatchesAreSavedWhileTheRunIsGoingAndTheRestOnClose() {
        UUID execution = executions.save(new PipelineExecution("dev-factory", "1", "{}")).getId();
        UUID run = UUID.randomUUID();
        try (var sink = store.open(execution, run, "implement")) {
            for (int i = 0; i < CodingTranscriptStore.BATCH_SIZE; i++) sink.add(text("entry " + i));
            assertThat(store.read(execution)).hasSize(CodingTranscriptStore.BATCH_SIZE);
            sink.add(text("last"));
            assertThat(store.read(execution)).hasSize(CodingTranscriptStore.BATCH_SIZE);
        }
        var entries = store.read(execution);
        assertThat(entries).hasSize(CodingTranscriptStore.BATCH_SIZE + 1)
                .allSatisfy(entry -> assertThat(entry.runId()).isEqualTo(run));
        assertThat(entries).extracting(CodingTranscriptStore.Entry::seq)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(0, 20).boxed().toList());
        assertThat(entries.getLast().detail().path("text").asString()).isEqualTo("last");
        assertThat(entries.getLast().stepId()).isEqualTo("implement");
    }

    @Test
    void aFailedWriteLeavesANoteAndLaterEntriesAreStillSaved() {
        UUID execution = executions.save(new PipelineExecution("dev-factory", "1", "{}")).getId();
        try (var sink = store.open(execution, UUID.randomUUID(), "implement")) {
            sink.add(text("first"));
            sink.close();
            sink.add(new CodingTranscripts.Entry("a kind that is far too long for the column", Instant.now(),
                    Map.of("text", "lost")));
            sink.close();
            sink.add(text("after the failure"));
        }
        assertThat(store.read(execution)).extracting(entry -> entry.detail().path("text").asString())
                .containsExactly("first",
                        "1 transcript entries could not be saved here, so the transcript is incomplete.",
                        "after the failure");
    }

    @Test
    void runsAreReadInTheOrderTheyStartedAndRetentionRemovesThem() {
        UUID execution = executions.save(new PipelineExecution("dev-factory", "1", "{}")).getId();
        UUID later = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID earlier = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        try (var sink = store.open(execution, earlier, "implement")) {
            sink.add(new CodingTranscripts.Entry("prompt", Instant.parse("2026-10-27T09:00:00Z"),
                    Map.of("text", "initial")));
        }
        try (var sink = store.open(execution, later, "pr-follow-up")) {
            sink.add(new CodingTranscripts.Entry("prompt", Instant.parse("2026-10-27T10:00:00Z"),
                    Map.of("text", "feedback")));
        }
        assertThat(store.read(execution)).extracting(CodingTranscriptStore.Entry::runId)
                .containsExactly(earlier, later);

        jdbc.sql("DELETE FROM pipeline_execution WHERE id = :id").param("id", execution).update();
        assertThat(store.read(execution)).isEmpty();
    }

    private static CodingTranscripts.Entry text(String text) {
        return new CodingTranscripts.Entry("text", Instant.now(), Map.of("text", text));
    }
}
