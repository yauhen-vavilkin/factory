package org.folio.factory.devfactory;

import java.nio.file.Path;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.decision.DecisionAnswerValidator;
import org.folio.factory.devfactory.decision.DecisionWorker;
import org.folio.factory.devfactory.delivery.DeliveryWorker;
import org.folio.factory.devfactory.delivery.GitHubDeliveryClient;
import org.folio.factory.devfactory.delivery.TrustedDeliveryService;
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
import org.folio.factory.devfactory.pi.PiCodingRunner;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.api.ProcessSessionFactory;
import org.folio.factory.sandbox.harness.CodingHarness;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

/** Dev Factory plugin wiring: inbox properties and the coding worker bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InboxProperties.class)
public class DevFactoryConfiguration {

  @Bean @ConditionalOnBean(ProcessSessionFactory.class)
  public PiCodingRunner piCodingRunner(ProcessSessionFactory sessions) { return new PiCodingRunner(sessions); }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piPrepareWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking) {
    return new PiWorker("pi-prepare-worker", s, r, token, gateway, thinking);
  }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piCodingWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking,
      RecoveryBundleStore recoveryStore) {
    return new PiWorker("pi-coding-worker", s, r, token, gateway, thinking, recoveryStore);
  }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piVerifyWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking) {
    return new PiWorker("pi-verify-worker", s, r, token, gateway, thinking);
  }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piRepairWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking,
      RecoveryBundleStore recoveryStore) {
    return new PiWorker("pi-repair-worker", s, r, token, gateway, thinking, recoveryStore);
  }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piReverifyWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking) {
    return new PiWorker("pi-reverify-worker", s, r, token, gateway, thinking);
  }
  @Bean @ConditionalOnBean({SandboxService.class, PiCodingRunner.class})
  public PiWorker piFinalizeWorker(SandboxService s, PiCodingRunner r,
      @Value("${factory.pi.model-token:}") String token,
      @Value("${factory.pi.gateway-url:http://factory-gateway:8080/v1}") String gateway,
      @Value("${factory.pi.thinking:high}") String thinking) {
    return new PiWorker("pi-finalize-worker", s, r, token, gateway, thinking);
  }

  /**
   * Trusted delivery holds the only GitHub write credential, scoped to delivery
   * ({@code factory.delivery.github.token}). It lives in the Factory process;
   * sandboxes and the coding runtime never receive it. Blank token: every
   * delivery is refused before any remote call. A non-blank fork owner delivers
   * to that owner's fork of the authoritative repository instead of the
   * repository itself.
   */
  @Bean
  public TrustedDeliveryService trustedDeliveryService(
      @Value("${factory.delivery.github.token:}") String token,
      @Value("${factory.delivery.github.fork-owner:}") String forkOwner,
      @Value("${factory.delivery.commit-author-name:Factory Developer Flow}") String authorName,
      @Value("${factory.delivery.commit-author-email:factory-developer-flow@users.noreply.github.com}")
      String authorEmail) {
    return new TrustedDeliveryService(new GitHubDeliveryClient(token), token, forkOwner, authorName,
        authorEmail);
  }

  @Bean
  public DeliveryWorker piDeliverWorker(TrustedDeliveryService delivery) {
    return new DeliveryWorker(delivery);
  }

  /** NEEDS_DECISION request end: persists the decision request before the flow's HITL gate. */
  @Bean
  public DecisionWorker decisionRequestWorker(TaskResolutionService resolver, HitlReviewRepository reviews) {
    return new DecisionWorker(DecisionWorker.REQUEST_WORKER, resolver, reviews);
  }

  /** NEEDS_DECISION resume end: binds the human answer into the effective task after the gate. */
  @Bean
  public DecisionWorker decisionResumeWorker(TaskResolutionService resolver, HitlReviewRepository reviews) {
    return new DecisionWorker(DecisionWorker.RESUME_WORKER, resolver, reviews);
  }

  @Bean
  public DecisionAnswerValidator decisionAnswerValidator(ArtifactStore artifactStore,
                                                         HitlReviewRepository reviews) {
    return new DecisionAnswerValidator(artifactStore, reviews);
  }

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
