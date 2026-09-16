package org.folio.factory.devfactory;

import static org.assertj.core.api.Assertions.assertThat;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.devfactory.admission.TaskAdmissionService;
import org.folio.factory.devfactory.inbox.FileInboxTrigger;
import org.folio.factory.devfactory.inbox.InboxTaskFileParser;
import org.folio.factory.devfactory.jira.JiraTaskService;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevFactoryConfigurationWiringTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(DevFactoryConfiguration.class)
                    .withBean(SandboxService.class, () -> Mockito.mock(SandboxService.class))
                    .withBean(CodingHarness.class, () -> Mockito.mock(CodingHarness.class))
                    .withBean(FrontmatterCodec.class, FrontmatterCodec::new)
                    .withBean(ArtifactStore.class, () -> Mockito.mock(ArtifactStore.class))
                    .withBean(StateManager.class, () -> Mockito.mock(StateManager.class))
                    .withBean(HitlReviewRepository.class, () -> Mockito.mock(HitlReviewRepository.class))
                    .withBean(PipelineRouter.class, () -> Mockito.mock(PipelineRouter.class))
                    .withBean(JiraConnector.class, () -> Mockito.mock(JiraConnector.class));

    @Test
    void exposesInboxTaskFileParserAsBean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InboxTaskFileParser.class);
            // File inbox and Jira intake share one admission boundary.
            assertThat(context).hasSingleBean(TaskAdmissionService.class);
            assertThat(context).hasSingleBean(JiraTaskService.class);
        });
    }

    @Test
    void triggerWiredWithoutTestSubstitutes() {
        runner.withUserConfiguration(FileInboxTrigger.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(FileInboxTrigger.class);
                    assertThat(context).hasSingleBean(InboxTaskFileParser.class);
                });
    }
}
