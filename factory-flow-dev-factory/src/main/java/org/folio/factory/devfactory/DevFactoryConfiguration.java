package org.folio.factory.devfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.decision.DevArtifactAmendmentValidator;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.feedback.FeedbackDecisionPolicy;
import org.folio.factory.devfactory.feedback.FeedbackGit;
import org.folio.factory.devfactory.feedback.FeedbackPrMonitor;
import org.folio.factory.devfactory.feedback.FeedbackWorker;
import org.folio.factory.devfactory.feedback.GitHubPrFeedbackGateway;
import org.folio.factory.devfactory.feedback.PrFeedbackGateway;
import org.folio.factory.devfactory.repository.BaseRefResolver;
import org.folio.factory.devfactory.repository.GitBaseRefResolver;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.worker.CodingDecisionGateWorker;
import org.folio.factory.devfactory.worker.DecisionGateWorker;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopContinuationWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.InitialCodingAttempt;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.IntakeWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Map;

/**
 * Developer Flow plugin wiring. The flow itself is data ({@code flows/dev-factory.yaml}); this
 * class only assembles the worker beans the descriptor references.
 */
@Configuration
@EnableConfigurationProperties({
    DevFactoryProperties.class,
    DevRuntimeProperties.class,
    DevDeliveryProperties.class
})
public class DevFactoryConfiguration {

    @Bean
    public DockerWorkloads devDockerWorkloads() {
        return new DockerWorkloads();
    }

    @Bean
    public CandidateFreezer devCandidateFreezer() {
        return new CandidateFreezer();
    }

    @Bean
    public CodingRuntime devCodingRuntime(DevRuntimeProperties runtime) {
        return switch (runtime.coding().runtime()) {
            case "pi" -> new PiCodingRuntime(runtime.coding());
            case "claude-code" -> new ClaudeCodeCodingRuntime(runtime.coding().claudeCode());
            default ->
                    throw new IllegalStateException(
                            "Unsupported coding runtime '" + runtime.coding().runtime() + "'");
        };
    }

    @Bean
    public InitialCodingAttempt initialCodingAttempt(
            DevFactoryProperties properties,
            DevRuntimeProperties runtime,
            DockerWorkloads docker,
            CandidateFreezer freezer,
            CodingRuntime coding,
            FrontmatterCodec codec,
            AuditLog audit,
            ObjectProvider<AttemptPricing> pricing,
            ObjectProvider<SelectedRepositoryPreflight> repositoryPreflight) {
        return new InitialCodingAttempt(
                properties,
                runtime,
                docker,
                freezer,
                coding,
                codec,
                audit,
                pricing.getIfAvailable(() -> model -> Map.of()),
                repositoryPreflight.getIfAvailable(SelectedRepositoryPreflight::none));
    }

    @Bean
    public DevelopWorker devDevelopWorker(InitialCodingAttempt attempt, FrontmatterCodec codec) {
        return new DevelopWorker(attempt, codec);
    }

    @Bean
    public CodingDecisionGateWorker devCodingDecisionGateWorker(ConditionalDecisionGate gate) {
        return new CodingDecisionGateWorker(gate);
    }

    @Bean
    public DevelopContinuationWorker devDevelopContinuationWorker(
            InitialCodingAttempt develop, FrontmatterCodec codec) {
        return new DevelopContinuationWorker(develop, codec);
    }

    @Bean
    public CandidateVerifier devCandidateVerifier(
            DevFactoryProperties properties,
            DevRuntimeProperties runtime,
            CandidateFreezer freezer,
            DockerWorkloads docker) {
        return new CandidateVerifier(properties, runtime, freezer, docker);
    }

    @Bean
    public VerifyWorker devVerifyWorker(CandidateVerifier verifier) {
        return new VerifyWorker(verifier);
    }

    @Bean
    public CandidateDelivery devCandidateDelivery(CandidateFreezer freezer) {
        return new CandidateDelivery(freezer);
    }

    @Bean
    public DeliveryWorker devDeliveryWorker(
            DevFactoryProperties repositories,
            DevRuntimeProperties runtime,
            DevDeliveryProperties properties,
            CandidateDelivery delivery,
            GitHubProperties githubProperties,
            @Qualifier("gitHubConnector") GitHubConnector github,
            FrontmatterCodec frontmatter,
            StateManager state) {
        return new DeliveryWorker(
                repositories,
                runtime,
                properties,
                delivery,
                githubProperties,
                github,
                frontmatter,
                state);
    }

    @Bean
    public RepositoryPolicy devRepositoryPolicy(DevFactoryProperties properties) {
        return new RepositoryPolicy(properties);
    }

    @Bean
    public BaseRefResolver devBaseRefResolver(DevFactoryProperties properties) {
        return new GitBaseRefResolver(properties.gitBaseUrl());
    }

    @Bean
    public ConditionalDecisionGate devConditionalDecisionGate(
            StateManager stateManager,
            FlowRegistry flowRegistry,
            HitlGateOpener gateOpener,
            HitlReviewRepository reviews) {
        return new ConditionalDecisionGate(stateManager, flowRegistry, gateOpener, reviews);
    }

    @Bean
    public DevArtifactAmendmentValidator devArtifactAmendmentValidator(
            FrontmatterCodec codec, RepositoryPolicy policy) {
        return new DevArtifactAmendmentValidator(codec, policy);
    }

    @Bean
    public IntakeWorker devIntakeWorker(
            JiraConnector jiraConnector,
            RepositoryPolicy policy,
            FrontmatterCodec codec,
            CodingRuntime runtime,
            AuditLog audit) {
        return new IntakeWorker(jiraConnector, policy, codec, runtime, audit);
    }

    @Bean
    public DecisionGateWorker devDecisionGateWorker(
            ConditionalDecisionGate gate, FrontmatterCodec codec) {
        return new DecisionGateWorker(gate, codec);
    }

    @Bean
    public IntakeResolveWorker devIntakeResolveWorker(
            RepositoryPolicy policy, BaseRefResolver baseRefResolver, FrontmatterCodec codec) {
        return new IntakeResolveWorker(policy, baseRefResolver, codec);
    }

    @Bean
    public PrFeedbackGateway prFeedbackGateway(
            GitHubProperties properties, RestClient.Builder builder) {
        return new GitHubPrFeedbackGateway(properties, builder);
    }

    @Bean
    public FeedbackGit feedbackGit(CandidateFreezer freezer) {
        return new FeedbackGit(freezer);
    }

    @Bean
    public FeedbackDecisionPolicy feedbackDecisionPolicy(FlowRegistry flows) {
        return new FeedbackDecisionPolicy(flows);
    }

    @Bean
    public FeedbackActions feedbackActions(
            ArtifactStore artifacts,
            PipelineExecutionRepository executions,
            HitlReviewRepository reviews,
            HitlDecisionService decisions,
            FeedbackDecisionPolicy policy,
            PrFeedbackGateway gateway,
            PlatformTransactionManager transactions) {
        return new FeedbackActions(
                artifacts, executions, reviews, decisions, policy, gateway, transactions);
    }

    @Bean
    public ThreadPoolTaskExecutor feedbackPrExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("factory-pr-monitor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(35);
        return executor;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "factory.engine",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public FeedbackPrMonitor feedbackPrMonitor(
            PipelineExecutionRepository executions,
            FlowRegistry flows,
            FeedbackActions actions,
            @Qualifier("feedbackPrExecutor") ThreadPoolTaskExecutor executor) {
        return new FeedbackPrMonitor(executions, flows, actions, executor, Clock.systemUTC());
    }

    @Bean
    public AgentWorker feedbackInitializer() {
        return new AgentWorker() {
            public String id() {
                return "dev-pr-initialize";
            }

            public AgentResult execute(AgentContext context) {
                return new AgentResult(FeedbackArtifacts.initial(), Map.of());
            }
        };
    }

    @Bean
    public FeedbackWorker feedbackWorker(
            DevFactoryProperties repositories,
            DevDeliveryProperties delivery,
            DevRuntimeProperties runtime,
            GitHubProperties github,
            PrFeedbackGateway gateway,
            CandidateFreezer freezer,
            CandidateVerifier verifier,
            CodingRuntime coding,
            DockerWorkloads docker,
            FeedbackGit git,
            StateManager state,
            AuditLog audit,
            ObjectProvider<AttemptPricing> pricing) {
        return new FeedbackWorker(
                repositories,
                delivery,
                runtime,
                github,
                gateway,
                freezer,
                verifier,
                coding,
                docker,
                git,
                state,
                audit,
                pricing.getIfAvailable(() -> model -> Map.of()));
    }
}
