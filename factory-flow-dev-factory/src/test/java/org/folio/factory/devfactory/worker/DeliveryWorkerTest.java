package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DeliveryWorkerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void unverifiedResultCannotReachGitOrGitHub() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var result = worker.execute(context(Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFICATION_FAILED\"}",
                DevelopWorker.CANDIDATE, "{}", VerifyWorker.RECEIPT, "{}",
                IntakeResolveWorker.TASK_BRIEF, "ignored")));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY)).contains("NOT_RUN");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void missingTokenReturnsAnExplicitDeliveryGateAfterVerification() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, null));
        String patch = "";
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("execution", "sidecar", candidate.baseSha(), candidate.treeSha(),
                candidate.patchSha256(), "unit", "image", List.of("mvn", "test"), "fresh", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        var result = worker.execute(contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief)));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        verifyNoInteractions(delivery, github);
    }

    private static DeliveryWorker worker(CandidateDelivery delivery, GitHubConnector github,
                                         GitHubProperties githubProperties) {
        var repository = new DevFactoryProperties.Repository("folio-org/folio-module-sidecar", "master", "image",
                "unit", List.of("MODSIDECAR"), List.of());
        var repositories = new DevFactoryProperties("https://github.com",
                new TreeMap<>(Map.of("sidecar", repository)));
        var targets = new DevDeliveryProperties(Map.of("sidecar",
                new DeliveryTarget("user/folio-module-sidecar", "master", true)), true);
        return new DeliveryWorker(repositories, targets, delivery, githubProperties, github, new FrontmatterCodec());
    }

    private static AgentContext context(Map<String, String> inputs) {
        return contextWithId(UUID.randomUUID().toString(), inputs);
    }

    private static AgentContext contextWithId(String id, Map<String, String> inputs) {
        Map<String, ArtifactContent> artifacts = new java.util.HashMap<>();
        inputs.forEach((name, content) -> artifacts.put(name, new ArtifactContent(name, 1,
                name.endsWith(".json") ? "application/json" : "text/markdown", content)));
        return new AgentContext(UUID.fromString(id.equals("execution")
                ? "00000000-0000-0000-0000-000000000001" : id), "deliver", artifacts, null, Map.of(), List.of());
    }
}
