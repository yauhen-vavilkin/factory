package org.folio.factory.devfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraProperties;
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
import org.folio.factory.devfactory.delivery.DeliveryServices;
import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.feedback.FeedbackDecisionPolicy;
import org.folio.factory.devfactory.feedback.FeedbackGit;
import org.folio.factory.devfactory.feedback.FeedbackPrMonitor;
import org.folio.factory.devfactory.feedback.FeedbackServices;
import org.folio.factory.devfactory.feedback.FeedbackWorker;
import org.folio.factory.devfactory.feedback.GitHubPrFeedbackGateway;
import org.folio.factory.devfactory.feedback.PrFeedbackGateway;
import org.folio.factory.devfactory.repository.BaseRefResolver;
import org.folio.factory.devfactory.repository.GitBaseRefResolver;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.RunProfiles;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskGuard;
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
import org.springframework.core.env.Environment;
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
@EnableConfigurationProperties({DevFactoryProperties.class, DevRuntimeProperties.class, RunProfiles.Properties.class})
public class DevFactoryConfiguration {

    /** The application's hooks, or the no-op defaults when the module runs without the application. */
    @Bean
    public DevFactoryHooks devFactoryHooks(ObjectProvider<TaskAttemptPolicy> taskPolicy,
            ObjectProvider<AttemptPricing> pricing, ObjectProvider<CodingTranscripts> transcripts,
            ObjectProvider<SelectedRepositoryPreflight> repositoryPreflight) {
        return new DevFactoryHooks(taskPolicy.getIfAvailable(TaskAttemptPolicy::none),
                pricing.getIfAvailable(() -> model -> Map.of()), transcripts.getIfAvailable(CodingTranscripts::none),
                repositoryPreflight.getIfAvailable(SelectedRepositoryPreflight::none));
    }

    @Bean
    public TaskGuard devTaskGuard(DevFactoryHooks hooks) {
        return new TaskGuard(hooks.taskPolicy(), Clock.systemUTC());
    }

    @Bean
    public DevFlowSettings devFlowSettings(DevFactoryProperties repositories, DevRuntimeProperties runtime) {
        return new DevFlowSettings(repositories, runtime);
    }

    @Bean
    public CodingServices devCodingServices(CodingRuntime coding, DockerWorkloads docker, CandidateFreezer freezer) {
        return new CodingServices(coding, docker, freezer);
    }

    @Bean
    public DockerWorkloads devDockerWorkloads() {
        return new DockerWorkloads();
    }

    @Bean
    public CandidateFreezer devCandidateFreezer() {
        return new CandidateFreezer();
    }

    /** Secrets are read through the Environment by the variable name a profile gives; the file holds none. */
    @Bean
    public RunProfiles devRunProfiles(DevRuntimeProperties runtime, RunProfiles.Properties profiles,
            Environment environment) {
        return new RunProfiles(runtime.coding().claudeCode(), profiles, environment::getProperty);
    }

    @Bean
    public CodingRuntime devCodingRuntime(DevRuntimeProperties runtime, RunProfiles profiles) {
        return switch (runtime.coding().runtime()) {
            case "pi" -> new PiCodingRuntime(runtime.coding().pi());
            case "claude-code" -> new ClaudeCodeCodingRuntime(runtime.coding().claudeCode(), profiles);
            default -> throw new IllegalStateException(
                    "Unsupported coding runtime '" + runtime.coding().runtime() + "'");
        };
    }

    @Bean
    public InitialCodingAttempt initialCodingAttempt(DevFlowSettings settings, CodingServices services,
            FrontmatterCodec codec, AuditLog audit, DevFactoryHooks hooks, TaskGuard taskGuard) {
        return new InitialCodingAttempt(settings, services, codec, audit, hooks, taskGuard);
    }

    @Bean
    public DevelopWorker devDevelopWorker(InitialCodingAttempt attempt, FrontmatterCodec codec) {
        return new DevelopWorker(attempt, codec);
    }

    @Bean
    public CodingDecisionGateWorker devCodingDecisionGateWorker(ConditionalDecisionGate gate, TaskGuard taskGuard) {
        return new CodingDecisionGateWorker(gate, taskGuard);
    }

    @Bean
    public DevelopContinuationWorker devDevelopContinuationWorker(InitialCodingAttempt develop, FrontmatterCodec codec,
            TaskGuard taskGuard) {
        return new DevelopContinuationWorker(develop, codec, taskGuard);
    }

    @Bean
    public CandidateVerifier devCandidateVerifier(DevFactoryProperties properties, DevRuntimeProperties runtime,
            CandidateFreezer freezer, DockerWorkloads docker) {
        return new CandidateVerifier(properties, runtime, freezer, docker);
    }

    @Bean
    public VerifyWorker devVerifyWorker(CandidateVerifier verifier, DevFlowSettings settings, TaskGuard taskGuard) {
        return new VerifyWorker(verifier, settings, taskGuard);
    }

    @Bean
    public CandidateDelivery devCandidateDelivery(CandidateFreezer freezer) {
        return new CandidateDelivery(freezer);
    }

    @Bean
    public DeliveryServices devDeliveryServices(CandidateDelivery delivery,
            @Qualifier("gitHubConnector") GitHubConnector github, GitHubProperties githubProperties) {
        return new DeliveryServices(delivery, github, githubProperties);
    }

    @Bean
    public DeliveryWorker devDeliveryWorker(DevFlowSettings settings, DeliveryServices publication,
            JiraProperties jiraProperties, FrontmatterCodec frontmatter, StateManager state, TaskGuard taskGuard) {
        return new DeliveryWorker(settings, publication, jiraProperties, frontmatter, state, taskGuard);
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
    public DevArtifactAmendmentValidator devArtifactAmendmentValidator(FrontmatterCodec codec,
            RepositoryPolicy policy) {
        return new DevArtifactAmendmentValidator(codec, policy);
    }

    @Bean
    public IntakeWorker devIntakeWorker(JiraConnector jiraConnector, RepositoryPolicy policy, FrontmatterCodec codec,
            CodingRuntime runtime, AuditLog audit, TaskGuard taskGuard) {
        return new IntakeWorker(jiraConnector, policy, codec, runtime, audit, taskGuard);
    }

    @Bean
    public DecisionGateWorker devDecisionGateWorker(ConditionalDecisionGate gate, FrontmatterCodec codec,
            TaskGuard taskGuard) {
        return new DecisionGateWorker(gate, codec, taskGuard);
    }

    @Bean
    public IntakeResolveWorker devIntakeResolveWorker(RepositoryPolicy policy, BaseRefResolver baseRefResolver,
            FrontmatterCodec codec, TaskGuard taskGuard) {
        return new IntakeResolveWorker(policy, baseRefResolver, codec, taskGuard);
    }

    @Bean
    public PrFeedbackGateway prFeedbackGateway(GitHubProperties properties, RestClient.Builder builder) {
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
    public FeedbackActions feedbackActions(ArtifactStore artifacts, PipelineExecutionRepository executions,
            HitlReviewRepository reviews, HitlDecisionService decisions, FeedbackDecisionPolicy policy,
            PrFeedbackGateway gateway, PlatformTransactionManager transactions) {
        return new FeedbackActions(artifacts, executions, reviews, decisions, policy, gateway, transactions);
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
    @ConditionalOnProperty(prefix = "factory.engine", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FeedbackPrMonitor feedbackPrMonitor(PipelineExecutionRepository executions, FlowRegistry flows,
            FeedbackActions actions, @Qualifier("feedbackPrExecutor") ThreadPoolTaskExecutor executor) {
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
    public FeedbackServices feedbackServices(PrFeedbackGateway gateway, FeedbackGit git, GitHubProperties github) {
        return new FeedbackServices(gateway, git, github);
    }

    @Bean
    public FeedbackWorker feedbackWorker(DevFlowSettings settings, CodingServices services, FeedbackServices feedback,
            CandidateVerifier verifier, StateManager state, AuditLog audit, DevFactoryHooks hooks) {
        return new FeedbackWorker(settings, services, feedback, verifier, state, audit, hooks);
    }
}
