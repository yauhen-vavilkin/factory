package org.folio.factory.devfactory.feedback;

import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.mockito.Mockito.*;

/** Canonical catalog destinations must authorize the same feedback paths as legacy targets. */
class FeedbackCatalogTest {
    private final UUID executionId = UUID.randomUUID();
    private final PrFeedbackGateway gateway = mock(PrFeedbackGateway.class);
    private final CodingRuntime coding = mock(CodingRuntime.class);
    private final CandidateFreezer freezer = mock(CandidateFreezer.class);
    private final DockerWorkloads docker = mock(DockerWorkloads.class);
    private final PrFeedbackGateway.PullRequest pr = new PrFeedbackGateway.PullRequest(
            "fork/repo", 7, "factory/task", "main", "a".repeat(40),
            "https://github.com/fork/repo/pull/7", true);

    @Test
    void initializesPublishedPullRequestUsingNestedCatalogDestination() {
        var saved = new LinkedHashMap<>(initial());
        saved.put("dev_delivery.json", JSON.writeValueAsString(Map.of(
                "state", "DELIVERED", "repository", "fixture", "deliveryBranch", pr.branch(),
                "pullRequestUrl", pr.url())));
        when(gateway.read(pr.repository(), pr.number())).thenReturn(pr);

        var result = worker(true).execute(context(saved));
        var state = JSON.readValue(result.outputs().get(STATE), FeedbackState.class);

        assertThat(state.phase()).isEqualTo(FeedbackState.Phase.READY);
        assertThat(state.pr()).isEqualTo(pr);
        verifyNoInteractions(coding, freezer);
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "true, true", "false, true"})
    void followsCurrentCatalogAuthorizationDespiteAnUnrelatedIncompleteDestination(
            boolean authorized, boolean incompleteOtherTarget) {
        var comment = new PrFeedbackGateway.Comment("inline:1", "reviewer", "Correct value",
                "value.txt", 1, false, "now", "url");
        var state = new FeedbackState();
        state.pr = pr;
        state.initialized();
        state.apply(FeedbackActions.Action.FIX, new PrFeedbackGateway.Snapshot("selected", pr, List.of(comment)),
                "selected", List.of(comment.id()), "");
        var saved = new LinkedHashMap<>(initial());
        saved.put(STATE, JSON.writeValueAsString(state));
        when(gateway.status(pr)).thenReturn(PrFeedbackGateway.Status.OPEN);
        when(gateway.read(pr.repository(), pr.number())).thenReturn(pr);
        when(gateway.comments(pr)).thenReturn(new PrFeedbackGateway.Snapshot("changed", pr, List.of(comment)));
        when(coding.redact(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = worker(authorized, incompleteOtherTarget).execute(context(saved));
        var observed = JSON.readValue(result.outputs().get(STATE), FeedbackState.class);

        if (authorized) {
            assertThat(observed.phase()).isEqualTo(FeedbackState.Phase.REFRESH_REQUIRED);
            verify(gateway).comments(pr);
        } else {
            assertThat(observed.phase()).isEqualTo(FeedbackState.Phase.FAILURE);
            assertThat(observed.reason()).contains("PR target is no longer authorized");
            verify(gateway, never()).read(anyString(), anyInt());
            verify(gateway, never()).comments(any());
        }
        verify(coding, never()).code(any(), any(), anyInt());
        verify(coding, never()).code(any(), any(), anyInt(), any());
        verifyNoInteractions(freezer);
    }

    private FeedbackWorker worker(boolean authorized) {
        return worker(authorized, false);
    }

    private FeedbackWorker worker(boolean authorized, boolean incompleteOtherTarget) {
        var repository = new DevFactoryProperties.Repository("source/repo", "main", "build:image", "unit",
                List.of("TASK"), List.of(), new DeliveryTarget(pr.repository(), "main", authorized));
        var catalog = new TreeMap<>(Map.of("fixture", repository));
        if (incompleteOtherTarget) {
            catalog.put("unrelated", new DevFactoryProperties.Repository("source/other", "main", "build:image", "unit",
                    List.of("OTHER"), List.of(), new DeliveryTarget(null, null, true)));
        }
        var repositories = new DevFactoryProperties(null, catalog);
        var delivery = new DevDeliveryProperties(Map.of(), true);
        var runtime = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null);
        var executions = mock(StateManager.class);
        when(executions.get(executionId)).thenReturn(new PipelineExecution("dev-factory", VERSION, null));
        var audit = mock(AuditLog.class);
        when(audit.forExecution(executionId)).thenReturn(List.of());
        return new FeedbackWorker(repositories, delivery, runtime, new GitHubProperties(null, "fixture-token"),
                gateway, freezer, mock(CandidateVerifier.class), coding, docker, mock(FeedbackGit.class), executions,
                audit, model -> Map.of());
    }

    private AgentContext context(Map<String, String> saved) {
        var inputs = new LinkedHashMap<String, ArtifactContent>();
        saved.forEach((name, content) -> inputs.put(name, new ArtifactContent(name, 1, "application/json", content)));
        return new AgentContext(executionId, STEP, inputs, null, Map.of(), OUTPUTS,
                new AgentContext.Invocation(UUID.randomUUID(), false, () -> {}, detail -> {}));
    }
}
