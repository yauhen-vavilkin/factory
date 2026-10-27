package org.folio.factory.app.developer;

import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps coding transcripts in the database. Each run writes small batches while it is going, so a
 * Factory restart loses at most the last batch. A failed write is logged and leaves a note in the
 * transcript; it never fails the attempt.
 */
@Component
public class CodingTranscriptStore implements CodingTranscripts {
    private static final Logger log = LoggerFactory.getLogger(CodingTranscriptStore.class);
    static final int BATCH_SIZE = 20;
    static final Duration BATCH_AGE = Duration.ofSeconds(2);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public CodingTranscriptStore(JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    /** One stored entry; runs come in the order they started, entries in the order they arrived. */
    public record Entry(UUID runId, int seq, String stepId, String kind, Instant at, JsonNode detail) {
    }

    @Override
    public Sink open(UUID executionId, UUID runId, String stepId) {
        return new Batch(executionId, runId, stepId);
    }

    public List<Entry> read(UUID executionId) {
        return jdbc.sql("""
                        SELECT run_id, seq, step_id, kind, occurred_at, detail::text AS detail
                        FROM coding_transcript_entry
                        WHERE execution_id = :executionId
                        ORDER BY min(occurred_at) OVER (PARTITION BY run_id), run_id, seq
                        """)
                .param("executionId", executionId)
                .query((row, number) -> new Entry(
                        row.getObject("run_id", UUID.class),
                        row.getInt("seq"),
                        row.getString("step_id"),
                        row.getString("kind"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        json.readTree(row.getString("detail"))))
                .list();
    }

    private final class Batch implements Sink {
        private final UUID executionId;
        private final UUID runId;
        private final String stepId;
        private final List<CodingTranscripts.Entry> pending = new ArrayList<>();
        private Instant lastWrite = Instant.now();
        private int nextSeq;
        private int lost;

        private Batch(UUID executionId, UUID runId, String stepId) {
            this.executionId = executionId;
            this.runId = runId;
            this.stepId = stepId;
        }

        @Override
        public synchronized void add(CodingTranscripts.Entry entry) {
            pending.add(entry);
            if (pending.size() >= BATCH_SIZE || !Instant.now().isBefore(lastWrite.plus(BATCH_AGE))) write();
        }

        @Override
        public synchronized void close() {
            write();
        }

        private void write() {
            lastWrite = Instant.now();
            if (pending.isEmpty() && lost == 0) return;
            var rows = new ArrayList<CodingTranscripts.Entry>();
            if (lost > 0) {
                rows.add(new CodingTranscripts.Entry("note", Instant.now(), Map.of("text", lost
                        + " transcript entries could not be saved here, so the transcript is incomplete.")));
            }
            rows.addAll(pending);
            try {
                transactions.executeWithoutResult(status -> insert(rows));
                nextSeq += rows.size();
                lost = 0;
            } catch (RuntimeException e) {
                lost += pending.size();
                log.warn("Could not save {} transcript entries of run {} in execution {}: {}",
                        pending.size(), runId, executionId, e.getMessage());
            }
            pending.clear();
        }

        private void insert(List<CodingTranscripts.Entry> rows) {
            var values = String.join(", ", Collections.nCopies(rows.size(), "(?, ?, ?, ?, ?, ?, ?::jsonb)"));
            var parameters = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                parameters.addAll(List.of(executionId, runId, nextSeq + i, stepId, row.kind(),
                        OffsetDateTime.ofInstant(row.at(), ZoneOffset.UTC), json.writeValueAsString(row.detail())));
            }
            jdbc.sql("INSERT INTO coding_transcript_entry"
                            + " (execution_id, run_id, seq, step_id, kind, occurred_at, detail) VALUES " + values)
                    .params(parameters)
                    .update();
        }
    }
}
