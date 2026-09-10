package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import java.util.List;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.devfactory.inbox.FileInboxTrigger;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.DockerSandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"factory.mode=offline", "spring.ai.model.chat=none", "factory.engine.enabled=false",
        "factory.sandbox.mode=local",
        "factory.inbox.dir=${java.io.tmpdir}/factory-inbox-coherence",
        "factory.inbox.poll-interval-ms=60000"})
@Import(StubLlmConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class DevFactoryStartupCoherenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private ApplicationContext context;

    @Test
    void devFactoryFlowAndWorkersBootCoherently() {
        FlowRegistry flowRegistry = context.getBean(FlowRegistry.class);
        assertThat(flowRegistry.find("dev-factory")).isPresent();
        FlowDescriptor devFactory = flowRegistry.require("dev-factory");
        List<StepDescriptor> agentSteps = devFactory.agentChain().stream()
                .filter(step -> step.type() == StepType.AGENT).toList();
        assertThat(agentSteps).hasSize(2);
        assertThat(agentSteps.get(0).stepId()).isEqualTo("coding");
        assertThat(agentSteps.get(0).workerId()).isEqualTo("coding-worker");
        assertThat(agentSteps.get(1).stepId()).isEqualTo("finalize");
        assertThat(agentSteps.get(1).workerId()).isEqualTo("dev-factory-finalizer");

        AgentWorkerRegistry workers = context.getBean(AgentWorkerRegistry.class);
        assertThat(workers.require("coding-worker").id()).isEqualTo("coding-worker");
        assertThat(workers.require("dev-factory-finalizer").id()).isEqualTo("dev-factory-finalizer");

        assertThat(context.getBeansOfType(SandboxService.class)).hasSize(1);
        assertThat(context.getBean(SandboxService.class)).isInstanceOf(LocalSandboxService.class);
        assertThat(context.getBeansOfType(DockerClient.class)).isEmpty();
        assertThat(context.getBeansOfType(DockerSandboxService.class)).isEmpty();
        assertThat(context.getBeansOfType(FileInboxTrigger.class)).hasSize(1);
    }
}
