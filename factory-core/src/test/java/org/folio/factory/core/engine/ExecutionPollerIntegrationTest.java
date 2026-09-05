package org.folio.factory.core.engine;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end through the scheduled poller: a PENDING execution is picked up and
 * driven to completion without any manual engine calls.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "factory.engine.enabled=true",
        "factory.engine.poll-interval-ms=250"
})
@Testcontainers
@DirtiesContext
class ExecutionPollerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StateManager stateManager;

    @Test
    void pollerPicksUpPendingExecutionAndCompletesIt() {
        PipelineExecution execution = stateManager.createExecution(
                "fake-simple", "1.0.0", "{\"issueKey\":\"POLL-1\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(execution.getId()).getStatus())
                        .isEqualTo(ExecutionStatus.COMPLETED));
    }
}
