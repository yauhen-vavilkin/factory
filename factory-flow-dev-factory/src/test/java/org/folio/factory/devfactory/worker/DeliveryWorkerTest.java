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
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
                Instant.EPOCH.plusSeconds(1), 0, 1, 1, 0, 0, "PASS", "ok");
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

    @Test
    void permanentDeliveryFailureReturnsBlockedWithoutEngineRetry() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, "token"));
        String patch = "diff --git a/a b/a\n";
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenThrow(new DeliveryBlockedException("Destination base no longer contains the candidate base"));

        var result = worker.execute(contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief)));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "no longer contains");
        verifyNoInteractions(github);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentPullRequestRejectionPreservesDeliveredIdentity(boolean lookupRejected) {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/modsidecar-196-12345678",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        var rejection = new HttpClientErrorException(HttpStatus.FORBIDDEN, "sensitive remote diagnostic");
        if (lookupRejected) {
            when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenThrow(rejection);
        } else {
            when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
            when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(rejection);
        }

        var result = worker.execute(verifiedContext());

        for (String artifact : List.of(DeliveryWorker.DELIVERY, VerifyWorker.RESULT)) {
            var output = json.readTree(result.outputs().get(artifact));
            assertThat(output.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
            assertThat(output.path("deliveryRepository").asString()).isEqualTo(delivered.repository());
            assertThat(output.path("deliveryBranch").asString()).isEqualTo(delivered.branch());
            assertThat(output.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
            assertThat(output.path("pullRequestState").asString()).isEqualTo("BLOCKED");
            assertThat(output.path("reason").asString()).contains("branch and commit delivered", "HTTP 403")
                    .doesNotContain("sensitive remote diagnostic");
        }
    }

    @Test
    void transientPullRequestFailureRetriesAndReusesExistingPullRequest() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/modsidecar-196-12345678",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of("https://github.com/user/folio-module-sidecar/pull/1"));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(HttpClientErrorException.class);
        var result = json.readTree(worker.execute(context).outputs().get(VerifyWorker.RESULT));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/1");
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString());
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private AgentContext verifiedContext() {
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        return contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief));
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
                ? "00000000-0000-0000-0000-000000000001" : id), "publish", artifacts, null, Map.of(), List.of());
    }
}
