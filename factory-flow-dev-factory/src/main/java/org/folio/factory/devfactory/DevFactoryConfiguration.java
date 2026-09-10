package org.folio.factory.devfactory;

import java.nio.file.Path;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.inbox.InboxProperties;
import org.folio.factory.devfactory.inbox.InboxTaskFileParser;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.resolution.GitHubRepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryCatalog;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.folio.factory.devfactory.worker.CodingWorker;
import org.folio.factory.devfactory.worker.DevFactoryFinalizer;
import org.folio.factory.devfactory.worker.recovery.RecoveryBundleStore;
import org.folio.factory.devfactory.pi.PiWorker;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Dev Factory plugin wiring: inbox properties and the coding worker bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InboxProperties.class)
public class DevFactoryConfiguration {

  @Bean public PiWorker piPrepareWorker() { return new PiWorker("pi-prepare-worker"); }
  @Bean public PiWorker piCodingWorker() { return new PiWorker("pi-coding-worker"); }
  @Bean public PiWorker piVerifyWorker() { return new PiWorker("pi-verify-worker"); }
  @Bean public PiWorker piFinalizeWorker() { return new PiWorker("pi-finalize-worker"); }

  @Bean
  public InboxTaskFileParser inboxTaskFileParser() {
    return new InboxTaskFileParser();
  }

  @Bean
  public RepositoryCatalog repositoryCatalog() {
    return new RepositoryCatalog();
  }

  @Bean
  public RepositoryAccess repositoryAccess() {
    return new GitHubRepositoryAccess();
  }

  @Bean
  public TrustedProfileCatalog trustedProfileCatalog() {
    return new TrustedProfileCatalog();
  }

  @Bean
  public TaskResolutionService taskResolutionService(RepositoryCatalog repositoryCatalog,
      RepositoryAccess repositoryAccess, TrustedProfileCatalog trustedProfileCatalog) {
    return new TaskResolutionService(repositoryCatalog, repositoryAccess, trustedProfileCatalog);
  }

  /**
   * T25: the recovery root is configurable via {@code factory.dev.recovery-root}
   * and must live outside the temp workDir and the sandbox workspace root —
   * both are destroyed, so only the bundle store preserves runs across
   * attempts. Blank (the shipped default) falls back to the store's default
   * under {@code java.io.tmpdir}. Exposed as a bean so the engine can inject
   * it as its {@link org.folio.factory.core.engine.StepRecoveryStore} and
   * discard acknowledged attempts' bundles.
   */
  @Bean
  public RecoveryBundleStore recoveryBundleStore(
      @Value("${factory.dev.recovery-root:}") String recoveryRoot) {
    Path root = recoveryRoot == null || recoveryRoot.isBlank()
        ? RecoveryBundleStore.DEFAULT_ROOT : Path.of(recoveryRoot.trim());
    return new RecoveryBundleStore(root);
  }

  @Bean
  public CodingWorker codingWorker(SandboxService sandboxService, CodingHarness codingHarness,
                                   FrontmatterCodec frontmatterCodec,
                                   RecoveryBundleStore recoveryBundleStore) {
    return new CodingWorker(sandboxService, codingHarness, frontmatterCodec,
        recoveryBundleStore);
  }

  @Bean
  public DevFactoryFinalizer devFactoryFinalizer(ArtifactStore artifactStore,
                                                 FrontmatterCodec frontmatterCodec) {
    return new DevFactoryFinalizer(artifactStore, frontmatterCodec);
  }
}
