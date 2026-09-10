package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import org.folio.factory.app.OfflineLlmConfiguration.OfflineScriptedChatModel;
import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.DockerSandboxService;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"factory.engine.enabled=false",
        "factory.connectors.jira.base-url=https://jira.invalid",
        "factory.connectors.github.base-url=https://github.invalid",
        "factory.connectors.github.token=must-not-enable",
        "factory.connectors.testrail.base-url=https://testrail.invalid"})
@ActiveProfiles("offline")
@org.junit.jupiter.api.Tag("integration")
@Testcontainers
class LocalModeAppContextTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private ApplicationContext context;

    @Test
    void localModeBootsFullContextWithTools() {
        assertThat(context.getBeansOfType(SandboxService.class)).hasSize(1);
        assertThat(context.getBean(SandboxService.class)).isInstanceOf(DockerSandboxService.class);
        assertThat(context.getBeansOfType(ReadTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ListTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApplyPatchTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ExecTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(GitDiffTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(DockerClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(OfflineScriptedChatModel.class)).hasSize(1);
        assertThat(context.getBean(OfflineScriptedChatModel.class).callCount()).isZero();
        assertThat(context.getBeansOfType(ConnectorHealth.class).values())
                .allMatch(connector -> !connector.isConfigured());
    }
}
