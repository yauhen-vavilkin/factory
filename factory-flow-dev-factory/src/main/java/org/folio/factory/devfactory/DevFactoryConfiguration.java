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
    public IntakeWorker devIntakeWorker(JiraConnector jiraConnector, RepositoryPolicy policy, FrontmatterCodec codec) {
        return new IntakeWorker(jiraConnector, policy, codec);
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
}
