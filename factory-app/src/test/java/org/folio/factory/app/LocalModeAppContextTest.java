package org.folio.factory.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.core.DockerSandboxService;
import org.folio.factory.sandbox.core.LocalSandboxService;
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
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false",
        "factory.sandbox.mode=local"})
@Import(StubLlmConfiguration.class)
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
        assertThat(context.getBean(SandboxService.class)).isInstanceOf(LocalSandboxService.class);
        assertThat(context.getBeansOfType(ReadTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ListTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApplyPatchTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(ExecTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(GitDiffTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestTool.class)).hasSize(1);
        assertThat(context.getBeansOfType(DockerClient.class)).isEmpty();
        assertThat(context.getBeansOfType(DockerSandboxService.class)).isEmpty();
    }
}
