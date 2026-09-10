package org.folio.factory.core.trigger;

import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class PipelineRouterIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void routesMatchingEventWithFiltersAndRequiredFields() {
        var payload = json.readTree("""
                {"issueKey": "ERM-42",
                 "issue": {"fields": {"status": {"name": "Ready for QA"}}}}
                """);
        List<UUID> created = router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload));

        assertThat(created).hasSize(1);
        var execution = stateManager.get(created.getFirst());
        assertThat(execution.getFlowId()).isEqualTo("fake-webhook");
        assertThat(execution.getTriggerPayload()).contains("ERM-42");
    }

    @Test
    void skipsEventWhenFilterDoesNotMatch() {
        var payload = json.readTree("""
                {"issueKey": "ERM-42",
                 "issue": {"fields": {"status": {"name": "In Progress"}}}}
                """);
        assertThat(router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload))).isEmpty();
    }

    @Test
    void skipsEventWhenRequiredFieldMissing() {
        var payload = json.readTree("""
                {"issue": {"fields": {"status": {"name": "Ready for QA"}}}}
                """);
        assertThat(router.route(TriggerEvent.of("jira.issue.transitioned", "test", payload))).isEmpty();
    }

    @Test
    void manualRouteValidatesInputsAndBypassesContractMatching() {
        UUID id = router.routeManual("fake-webhook", json.readTree("{\"issueKey\": \"ERM-1\"}"));
        assertThat(stateManager.get(id).getFlowId()).isEqualTo("fake-webhook");

        assertThatThrownBy(() -> router.routeManual("fake-webhook", json.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }

    @Test
    void unknownEventTypeMatchesNothing() {
        assertThat(router.route(TriggerEvent.of("github.push", "test", json.readTree("{}")))).isEmpty();
    }
}
