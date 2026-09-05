package org.folio.factory.devfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.inbox.InboxProperties;
import org.folio.factory.devfactory.inbox.InboxTaskFileParser;
import org.folio.factory.devfactory.worker.CodingWorker;
import org.folio.factory.devfactory.worker.DevFactoryFinalizer;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dev Factory plugin wiring: inbox properties and the coding worker bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InboxProperties.class)
public class DevFactoryConfiguration {

  @Bean
  public InboxTaskFileParser inboxTaskFileParser() {
    return new InboxTaskFileParser();
  }

  @Bean
  public CodingWorker codingWorker(SandboxService sandboxService, CodingHarness codingHarness,
                                   FrontmatterCodec frontmatterCodec) {
    return new CodingWorker(sandboxService, codingHarness, frontmatterCodec);
  }

  @Bean
  public DevFactoryFinalizer devFactoryFinalizer(ArtifactStore artifactStore,
                                                 FrontmatterCodec frontmatterCodec) {
    return new DevFactoryFinalizer(artifactStore, frontmatterCodec);
  }
}
