package org.folio.factory.core.limits;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.repository.AuditEventRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Daily execution budget (COST-1) semantics at the router. Budget of 2, dedup on with
 * /issueKey identity. Each test runs in a rolled-back transaction, so tests are
 * independent even though the class shares one database container.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "factory.limits.max-executions-per-day=2",
        "factory.limits.dedup.enabled=true",
        "factory.limits.dedup.id-pointers=/issueKey"
})
@Testcontainers
@Transactional
class DailyBudgetIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    AuditEventRepository auditEvents;

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode fanoutPayload(String issueKey) {
        return json.readTree("{\"issueKey\": \"" + issueKey + "\"}");
    }

    @Test
    void route_lastBudgetSlotWithTwoMatchingFlows_createsBothInsteadOfPartialCreate() {
        stateManager.createExecution("fake-simple", "1.0.0", "{}");

        // One budget slot left, two flows match test.fanout. The budget must be a single
        // pre-flight check for the whole event: either every matching flow starts (slight
        // overshoot tolerated) or none does — never one created and then an exception.
        List<UUID> created = router.route(TriggerEvent.of("test.fanout", "test", fanoutPayload("ERM-1")));

        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(id -> assertThat(executions.findById(id)).isPresent());
    }

    @Test
    void route_budgetExhausted_throwsCreatesNothingAndAudits() {
        stateManager.createExecution("fake-simple", "1.0.0", "{}");
        stateManager.createExecution("fake-simple", "1.0.0", "{}");
        long before = executions.count();

        assertThatThrownBy(() -> router.route(TriggerEvent.of("test.fanout", "test", fanoutPayload("ERM-2"))))
                .isInstanceOf(DailyBudgetExceededException.class);

        assertThat(executions.count()).isEqualTo(before);
        assertThat(auditEvents.findTop200ByOrderByIdDesc())
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.EXECUTION_BUDGET_EXCEEDED);
    }

    @Test
    void routeManual_budgetExhausted_throws() {
        stateManager.createExecution("fake-simple", "1.0.0", "{}");
        stateManager.createExecution("fake-simple", "1.0.0", "{}");

        assertThatThrownBy(() -> router.routeManual("fake-simple", json.readTree("{}"), null))
                .isInstanceOf(DailyBudgetExceededException.class);
    }

    @Test
    void route_dedupHit_bypassesExhaustedBudget() {
        JsonNode webhookPayload = json.readTree("""
                {"issueKey": "ERM-9",
                 "issue": {"fields": {"status": {"name": "Ready for QA"}}}}
                """);
        List<UUID> first = router.route(TriggerEvent.of("jira.issue.transitioned", "test", webhookPayload));
        assertThat(first).hasSize(1);
        stateManager.createExecution("fake-simple", "1.0.0", "{}");
        long before = executions.count();

        // Re-fired trigger with the same payload identity: collapses onto the existing
        // run without consuming (or being refused by) the exhausted budget.
        List<UUID> second = router.route(TriggerEvent.of("jira.issue.transitioned", "test", webhookPayload));

        assertThat(second).containsExactly(first.getFirst());
        assertThat(executions.count()).isEqualTo(before);
        assertThat(auditEvents.findTop200ByOrderByIdDesc())
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.EXECUTION_DEDUPED);
    }
}
