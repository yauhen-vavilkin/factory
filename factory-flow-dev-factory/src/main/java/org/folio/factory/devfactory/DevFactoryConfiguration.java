package org.folio.factory.devfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.decision.ConditionalDecisionGate;
import org.folio.factory.devfactory.decision.DevArtifactAmendmentValidator;
import org.folio.factory.devfactory.repository.BaseRefResolver;
import org.folio.factory.devfactory.repository.GitBaseRefResolver;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.worker.DecisionGateWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.IntakeWorker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Developer Flow plugin wiring. The flow itself is data ({@code flows/dev-factory.yaml});
 * this class only assembles the worker beans the descriptor references.
 */
@Configuration
@EnableConfigurationProperties({DevFactoryProperties.class,
        org.folio.factory.devfactory.runtime.DevRuntimeProperties.class,
        org.folio.factory.devfactory.delivery.DevDeliveryProperties.class})
public class DevFactoryConfiguration {

    @Bean
    public org.folio.factory.devfactory.runtime.DockerWorkloads devDockerWorkloads() {
        return new org.folio.factory.devfactory.runtime.DockerWorkloads();
    }

    @Bean
    public org.folio.factory.devfactory.candidate.CandidateFreezer devCandidateFreezer() {
        return new org.folio.factory.devfactory.candidate.CandidateFreezer();
    }

    @Bean
    public org.folio.factory.devfactory.runtime.CodingRuntime devCodingRuntime(org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime) {
        return switch (runtime.coding().runtime()) {
            case "pi" -> new org.folio.factory.devfactory.runtime.PiCodingRuntime(runtime.coding());
            default -> throw new IllegalStateException("Unsupported coding runtime '"
                    + runtime.coding().runtime() + "'");
        };
    }

    @Bean
    public org.folio.factory.devfactory.worker.DevelopWorker devDevelopWorker(DevFactoryProperties properties,
            org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime,
            org.folio.factory.devfactory.runtime.DockerWorkloads docker,
            org.folio.factory.devfactory.candidate.CandidateFreezer freezer,
            org.folio.factory.devfactory.runtime.CodingRuntime coding, FrontmatterCodec codec,
            org.folio.factory.core.service.AuditLog audit) {
        return new org.folio.factory.devfactory.worker.DevelopWorker(properties, runtime, docker, freezer, coding, codec, audit);
    }

    @Bean
    public org.folio.factory.devfactory.worker.CodingDecisionGateWorker devCodingDecisionGateWorker(
            ConditionalDecisionGate gate) {
        return new org.folio.factory.devfactory.worker.CodingDecisionGateWorker(gate);
    }

    @Bean
    public org.folio.factory.devfactory.worker.DevelopContinuationWorker devDevelopContinuationWorker(
            org.folio.factory.devfactory.worker.DevelopWorker develop, FrontmatterCodec codec) {
        return new org.folio.factory.devfactory.worker.DevelopContinuationWorker(develop, codec);
    }

    @Bean
    public org.folio.factory.devfactory.verification.CandidateVerifier devCandidateVerifier(
            DevFactoryProperties properties, org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime,
            org.folio.factory.devfactory.candidate.CandidateFreezer freezer,
            org.folio.factory.devfactory.runtime.DockerWorkloads docker) {
        return new org.folio.factory.devfactory.verification.CandidateVerifier(properties, runtime, freezer, docker);
    }

    @Bean
    public org.folio.factory.devfactory.worker.VerifyWorker devVerifyWorker(
            org.folio.factory.devfactory.verification.CandidateVerifier verifier) {
        return new org.folio.factory.devfactory.worker.VerifyWorker(verifier);
    }

    @Bean
    public org.folio.factory.devfactory.delivery.CandidateDelivery devCandidateDelivery(
            org.folio.factory.devfactory.candidate.CandidateFreezer freezer) {
        return new org.folio.factory.devfactory.delivery.CandidateDelivery(freezer);
    }

    @Bean
    public org.folio.factory.devfactory.worker.DeliveryWorker devDeliveryWorker(
            DevFactoryProperties repositories,
            org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime,
            org.folio.factory.devfactory.delivery.DevDeliveryProperties properties,
            org.folio.factory.devfactory.delivery.CandidateDelivery delivery,
            org.folio.factory.connectors.github.GitHubProperties githubProperties,
            @org.springframework.beans.factory.annotation.Qualifier("gitHubConnector")
            org.folio.factory.connectors.github.GitHubConnector github,
            FrontmatterCodec frontmatter) {
        return new org.folio.factory.devfactory.worker.DeliveryWorker(repositories, runtime, properties, delivery,
                githubProperties, github, frontmatter);
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
    public ConditionalDecisionGate devConditionalDecisionGate(StateManager stateManager, FlowRegistry flowRegistry,
                                                              HitlGateOpener gateOpener, HitlReviewRepository reviews) {
        return new ConditionalDecisionGate(stateManager, flowRegistry, gateOpener, reviews);
    }

    @Bean
    public DevArtifactAmendmentValidator devArtifactAmendmentValidator(FrontmatterCodec codec, RepositoryPolicy policy) {
        return new DevArtifactAmendmentValidator(codec, policy);
    }

    @Bean
    public IntakeWorker devIntakeWorker(JiraConnector jiraConnector, RepositoryPolicy policy, FrontmatterCodec codec,
            org.folio.factory.devfactory.runtime.CodingRuntime runtime,
            org.folio.factory.core.service.AuditLog audit) {
        return new IntakeWorker(jiraConnector, policy, codec, runtime, audit);
    }

    @Bean
    public DecisionGateWorker devDecisionGateWorker(ConditionalDecisionGate gate, FrontmatterCodec codec) {
        return new DecisionGateWorker(gate, codec);
    }

    @Bean
    public IntakeResolveWorker devIntakeResolveWorker(RepositoryPolicy policy, BaseRefResolver baseRefResolver,
                                                      FrontmatterCodec codec) {
        return new IntakeResolveWorker(policy, baseRefResolver, codec);
    }
    @Bean
    public org.folio.factory.devfactory.feedback.PrFeedbackGateway prFeedbackGateway(
            org.folio.factory.connectors.github.GitHubProperties properties, org.springframework.web.client.RestClient.Builder builder) {
        return new org.folio.factory.devfactory.feedback.GitHubPrFeedbackGateway(properties, builder);
    }
    @Bean
    public org.folio.factory.devfactory.feedback.FeedbackGit feedbackGit(org.folio.factory.devfactory.candidate.CandidateFreezer freezer) {
        return new org.folio.factory.devfactory.feedback.FeedbackGit(freezer);
    }
    @Bean
    public org.folio.factory.devfactory.feedback.FeedbackDecisionPolicy feedbackDecisionPolicy(FlowRegistry flows) {
        return new org.folio.factory.devfactory.feedback.FeedbackDecisionPolicy(flows);
    }
    @Bean
    public org.folio.factory.devfactory.feedback.FeedbackActions feedbackActions(
            org.folio.factory.core.service.ArtifactStore artifacts,
            org.folio.factory.core.repository.PipelineExecutionRepository executions,
            HitlReviewRepository reviews, org.folio.factory.core.hitl.HitlDecisionService decisions,
            org.folio.factory.devfactory.feedback.FeedbackDecisionPolicy policy,
            org.folio.factory.devfactory.feedback.PrFeedbackGateway gateway,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        return new org.folio.factory.devfactory.feedback.FeedbackActions(artifacts, executions, reviews, decisions, policy, gateway, transactions);
    }
    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor feedbackPrExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("factory-pr-monitor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(35);
        return executor;
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "factory.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
    public org.folio.factory.devfactory.feedback.FeedbackPrMonitor feedbackPrMonitor(
            org.folio.factory.core.repository.PipelineExecutionRepository executions, FlowRegistry flows,
            org.folio.factory.devfactory.feedback.FeedbackActions actions,
            @org.springframework.beans.factory.annotation.Qualifier("feedbackPrExecutor")
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor) {
        return new org.folio.factory.devfactory.feedback.FeedbackPrMonitor(executions, flows, actions, executor, java.time.Clock.systemUTC());
    }
    @Bean
    public org.folio.factory.core.agent.AgentWorker feedbackInitializer() {
        return new org.folio.factory.core.agent.AgentWorker() {
            public String id() { return "dev-pr-initialize"; }
            public org.folio.factory.core.agent.AgentResult execute(org.folio.factory.core.agent.AgentContext context) {
                return new org.folio.factory.core.agent.AgentResult(org.folio.factory.devfactory.feedback.FeedbackArtifacts.initial(), java.util.Map.of());
            }
        };
    }
    @Bean
    public org.folio.factory.devfactory.feedback.FeedbackWorker feedbackWorker(DevFactoryProperties repositories,
            org.folio.factory.devfactory.delivery.DevDeliveryProperties delivery,
            org.folio.factory.devfactory.runtime.DevRuntimeProperties runtime,
            org.folio.factory.connectors.github.GitHubProperties github,
            org.folio.factory.devfactory.feedback.PrFeedbackGateway gateway,
            org.folio.factory.devfactory.candidate.CandidateFreezer freezer,
            org.folio.factory.devfactory.verification.CandidateVerifier verifier,
            org.folio.factory.devfactory.runtime.CodingRuntime coding,
            org.folio.factory.devfactory.runtime.DockerWorkloads docker,
            org.folio.factory.devfactory.feedback.FeedbackGit git, StateManager state,
            org.folio.factory.core.service.AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<org.folio.factory.devfactory.feedback.AttemptPricing> pricing) {
        return new org.folio.factory.devfactory.feedback.FeedbackWorker(repositories,delivery,runtime,github,gateway,
                freezer,verifier,coding,docker,git,state,audit,pricing.getIfAvailable(()->model->java.util.Map.of()));
    }

}
