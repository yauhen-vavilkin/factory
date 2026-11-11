package org.folio.factory.app.tasks.persistence;

import org.folio.factory.core.domain.PipelineExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The runs of the Executions list with their {@link RunState}, filtered by the same SQL the Dashboard counts. */
public interface RunStateRepository extends Repository<PipelineExecution, UUID> {

    String WHERE = " WHERE (:flow = '' OR e.flow_id = :flow) AND (:state = '' OR " + RunState.SQL + " = :state)"
            + " AND e.created_at >= :since";

    /** A run of the list: its id and the name of its {@link RunState}. */
    interface Row {
        UUID getId();

        String getState();
    }

    /**
     * The newest runs first, of those started at or after {@code since}. A blank {@code state} or {@code flow} does
     * not filter; {@code page} must be unsorted, since the order is in the query.
     */
    @Query(value = "SELECT e.id AS id, " + RunState.SQL + " AS state FROM " + RunState.FROM + WHERE
            + " ORDER BY e.created_at DESC, e.id",
            countQuery = "SELECT count(*) FROM " + RunState.FROM + WHERE,
            nativeQuery = true)
    Page<Row> search(@Param("state") String state, @Param("flow") String flow, @Param("since") OffsetDateTime since,
                     Pageable page);
}
