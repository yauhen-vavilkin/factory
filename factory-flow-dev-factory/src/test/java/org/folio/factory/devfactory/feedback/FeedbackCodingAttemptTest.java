package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedbackCodingAttemptTest {
    private static final String MODEL_KEY = "sk-test-0123456789abcdef";

    @TempDir Path source;
    private final CandidateFreezer freezer = mock(CandidateFreezer.class);
    private final CodingRuntime coding = mock(CodingRuntime.class);
    private final DockerWorkloads docker = mock(DockerWorkloads.class);
    private FeedbackWorker.Operation operation;

    @BeforeEach
    void agentFinishesARound() {
        var scope = mock(DockerWorkloads.StepScope.class);
        when(docker.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createSeeded(any(), any(), any(), any())).thenReturn(mock(DockerWorkloads.Workload.class));
        // The attempt removes its checkout when it ends.
        when(freezer.reconstruct(anyString(), any())).thenAnswer(call -> Files.createTempDirectory(source, "checkout"));
        when(coding.redact(anyString())).thenAnswer(call -> call.getArgument(0));
        when(coding.holdsSecret(anyString())).thenAnswer(
                call -> CodingRuntime.holdsAny(call.getArgument(0), List.of(MODEL_KEY, "codemie-proxy")));
        when(coding.code(any(), any(), anyInt(), any(), any()))
                .thenReturn(CodingOutcome.completed("Done", Map.of("inputTokens", 10L)));
        var state = new FeedbackState();
        state.attemptId = UUID.randomUUID().toString();
        state.pr = new PrFeedbackGateway.PullRequest("fork/repo", 7, "factory/task", "main", "a".repeat(40),
                "https://github.com/fork/repo/pull/7", true);
        operation = new FeedbackWorker.Operation(
                new AgentContext(UUID.randomUUID(), "pr-follow-up", Map.of(), null, Map.of(), List.of()),
                state, new HashMap<>(), "pr-feedback-1", new LinkedHashMap<>());
    }

    @Test
    void editsThatAddAModelsApiKeyAreNotSavedAndTheReasonNamesTheFile() {
        agentLeaves("src/main/resources/application.yml", "api-key: " + MODEL_KEY);

        assertThatThrownBy(this::runRound)
                .hasMessageStartingWith("Latest edits could not be saved; the previous confirmed patch is retained.")
                .hasMessageContaining(TaskStops.secretInChangesCause("src/main/resources/application.yml"))
                .hasMessageNotContaining("Start a new run")
                .hasMessageNotContaining(MODEL_KEY);
        assertThat(operation.outputs()).doesNotContainKey(FeedbackArtifacts.CANDIDATE);
        assertThat(operation.metrics()).containsEntry("inputTokens", 10L);
    }

    @Test
    void editsThatHoldOnlyTheShortPublicWordOfALocalProxyAreSaved() {
        agentLeaves("README.md", "Start the proxy with the token codemie-proxy.");

        runRound();

        assertThat(operation.outputs()).containsKey(FeedbackArtifacts.CANDIDATE);
    }

    private void agentLeaves(String file, String line) {
        var patch = "diff --git a/" + file + " b/" + file + "\n--- a/" + file + "\n+++ b/" + file
                + "\n@@ -1 +1,2 @@\n existing\n+" + line + "\n";
        when(freezer.freeze(anyString(), anyString(), anyString(), any(), anyBoolean())).thenReturn(new Candidate(
                "repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch, Candidate.UNVERIFIED));
    }

    private void runRound() {
        var attempt = new FeedbackCodingAttempt(freezer, coding, docker,
                new DevRuntimeProperties(Map.of(), null, 60, null, null), (modelId, model) -> Map.of(),
                CodingTranscripts.none());
        var request = new CodingRequest("TEST-1", "Summary", "Description", List.of(), List.of(), List.of(),
                List.of(), new CodingRequest.RepositoryTarget("repo", "upstream/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults(), null);
        var previous = new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(""), "",
                Candidate.UNVERIFIED);
        attempt.run(operation, request, previous, "file:///fork/repo.git", event -> { });
    }
}
