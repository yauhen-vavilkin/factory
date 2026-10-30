package org.folio.factory.core.trigger;

import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
@DirtiesContext
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

    @Test
    void preparedManualRoundTripPersistsFlowPayloadAndDedupKey() {
        var payload = json.readTree("{\"issueKey\": \"ERM-7\"}");

        var prepared = router.prepareManual("fake-webhook", payload, "pilot-attempt-1");
        UUID id = router.routePrepared(prepared);

        var execution = stateManager.get(id);
        assertThat(execution.getFlowId()).isEqualTo("fake-webhook");
        assertThat(execution.getTriggerPayload()).contains("ERM-7");
        assertThat(execution.getDedupKey()).isEqualTo("pilot-attempt-1");
    }

    @Test
    void routePreparedRevalidatesFlowIdentityAndDedupesWithinWindow() {
        var prepared = router.prepareManual("fake-webhook", json.readTree("{\"issueKey\": \"ERM-8\"}"), "k-1");

        assertThatThrownBy(() -> router.routePrepared(new PreparedManualTrigger("no-such-flow", prepared.payload(), "k-1")))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("no-such-flow");

        UUID first = router.routePrepared(prepared);
        UUID second = router.routePrepared(prepared);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void manualRouteStillDedupesOnExplicitKeyAfterPreparedSplit() {
        var payload = json.readTree("{\"issueKey\": \"ERM-9\"}");

        UUID first = router.routeManual("fake-webhook", payload, "manual-key");
        UUID second = router.routeManual("fake-webhook", payload, "manual-key");

        assertThat(second).isEqualTo(first);
        assertThat(stateManager.get(first).getDedupKey()).isEqualTo("manual-key");
    }
}
