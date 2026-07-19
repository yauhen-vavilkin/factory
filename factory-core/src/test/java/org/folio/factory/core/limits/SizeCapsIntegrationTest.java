package org.folio.factory.core.limits;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.repository.ArtifactRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Testcontainers
class SizeCapsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ArtifactRepository artifactRepository;

    @Autowired
    PipelineExecutionRepository executionRepository;

    @Autowired
    AuditLog auditLog;

    @Autowired
    StateManager stateManager;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    JsonMapper jsonMapper;

    private ArtifactStore storeWithArtifactCap(int maxArtifactBytes) {
        return new ArtifactStore(artifactRepository, auditLog,
                new LimitsProperties(null, null, maxArtifactBytes, null, null));
    }

    private PipelineRouter routerWithPayloadCap(int maxTriggerPayloadBytes) {
        var limits = new LimitsProperties(null, null, null, maxTriggerPayloadBytes,
                new LimitsProperties.Dedup(false, null, null));
        return new PipelineRouter(flowRegistry, stateManager, jsonMapper, limits,
                new DedupKeyDeriver(limits, jsonMapper), auditLog);
    }

    private UUID newExecution() {
        FlowDescriptor flow = flowRegistry.require("fake-simple");
        return stateManager.createExecution(flow.id(), flow.version(), null).getId();
    }

    @Test
    void put_contentOverArtifactByteCap_rejectedAndNotStored() {
        UUID executionId = newExecution();
        ArtifactStore store = storeWithArtifactCap(100);
        String content = "a".repeat(101);

        assertThatThrownBy(() -> store.put(executionId, "big.md", content, "text/markdown", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("big.md")
                .hasMessageContaining("101")
                .hasMessageContaining("100");

        assertThat(artifactRepository.findTopByExecutionIdAndNameOrderByVersionDesc(executionId, "big.md"))
                .isEmpty();
    }

    @Test
    void put_capMeasuresBytesNotChars() {
        UUID executionId = newExecution();
        ArtifactStore store = storeWithArtifactCap(100);

        assertThatThrownBy(() ->
                store.put(executionId, "accented.md", "é".repeat(60), "text/markdown", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");

        Artifact stored = store.put(executionId, "accented.md", "é".repeat(40), "text/markdown", "test");
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test
    void put_contentAtExactCap_stored() {
        UUID executionId = newExecution();

        Artifact stored = storeWithArtifactCap(100)
                .put(executionId, "exact.md", "a".repeat(100), "text/markdown", "test");

        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test
    void routeManual_payloadOverTriggerByteCap_rejectedBeforeAnyExecutionCreated() {
        PipelineRouter router = routerWithPayloadCap(64);
        var payload = jsonMapper.readTree("{\"note\": \"" + "x".repeat(80) + "\"}");
        long countBefore = executionRepository.count();

        assertThatThrownBy(() -> router.routeManual("fake-simple", payload, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeding");

        assertThat(executionRepository.count()).isEqualTo(countBefore);
    }

    @Test
    void routeManual_payloadUnderCap_created() {
        PipelineRouter router = routerWithPayloadCap(64);

        UUID id = router.routeManual("fake-simple", jsonMapper.readTree("{\"k\": \"v\"}"), null);

        assertThat(stateManager.get(id).getFlowId()).isEqualTo("fake-simple");
    }
}
