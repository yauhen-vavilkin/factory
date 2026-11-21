package org.folio.factory.app.web;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.presentation.FeedbackStanding;
import org.folio.factory.app.tasks.presentation.TaskUsage;
import org.folio.factory.app.tasks.presentation.TaskView;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.feedback.FeedbackActions;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.folio.factory.devfactory.feedback.PrFeedbackGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Where PR feedback stands for My tasks and the task page, from the stored state of the task's run. */
@ExtendWith(MockitoExtension.class)
class FeedbackUiTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String PR = "https://github.com/folio-org/mod-bar/pull/7";

    @Mock
    private FeedbackActions actions;

    @Mock
    private ArtifactStore artifactStore;

    @Mock
    private PipelineExecutionRepository executions;

    private FeedbackUi feedback;

    @BeforeEach
    void setUp() {
        feedback = new FeedbackUi(actions, artifactStore, executions);
    }

    @Test
    void aPullRequestClosedWithoutMergeIsClosed() {
        givenRun(ExecutionStatus.AWAITING_HITL, "{\"phase\":\"STOPPED\"}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.CLOSED);
    }

    @Test
    void aFinishedTaskWhosePullRequestWasMergedIsMerged() {
        givenRun(ExecutionStatus.COMPLETED, "{\"phase\":\"FINISHED\",\"completionReason\":\"MERGED\"}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.MERGED);
    }

    @Test
    void aTaskFinishedForAnotherReasonHasNothingToSay() {
        givenRun(ExecutionStatus.COMPLETED, "{\"phase\":\"FINISHED\",\"completionReason\":\"CLOSED_WITHOUT_MERGE\"}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.NONE);
    }

    @Test
    void theAgentsQuestionWaitsForYou() {
        givenRun(ExecutionStatus.AWAITING_HITL, "{\"phase\":\"QUESTION\",\"round\":1}",
                FeedbackActions.Action.ANSWER);

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.WAITS_FOR_YOU);
    }

    @Test
    void newPrCommentsToChooseFromWaitForYou() {
        var stateJson = "{\"phase\":\"READY\"}";
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution(FeedbackArtifacts.VERSION,
                ExecutionStatus.AWAITING_HITL)));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(stateArtifact(stateJson)));
        var comment = new PrFeedbackGateway.Comment("inline:2", "reviewer", "Rename the test", "AppTest.java", 214,
                false, "", PR + "#discussion_r2");
        var snapshot = new PrFeedbackGateway.Snapshot("s1", null, List.of(comment));
        when(actions.view(EXECUTION_ID)).thenReturn(new FeedbackActions.View(
                FeedbackArtifacts.JSON.readValue(stateJson, FeedbackState.class), 1, null,
                List.of(FeedbackActions.Action.FIX), snapshot));

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.WAITS_FOR_YOU);
    }

    @Test
    void aRoundThatCodesWhileTheRunWorksIsWorking() {
        givenRun(ExecutionStatus.RUNNING, "{\"phase\":\"CODE\",\"round\":1}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.WORKING);
    }

    @Test
    void aRunThatWaitsForCommentsWithNoneToFixHasNothingToSay() {
        givenRun(ExecutionStatus.AWAITING_HITL, "{\"phase\":\"READY\"}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.NONE);
    }

    @Test
    void aSkippedPrFeedbackHasNothingToSay() {
        givenRun(ExecutionStatus.COMPLETED, "{\"phase\":\"SKIPPED\"}");

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.NONE);
    }

    @Test
    void aRunOfAnotherFlowVersionHasNoPrFeedback() {
        var execution = execution("0.8.0", ExecutionStatus.AWAITING_HITL);
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(stateArtifact("{\"phase\":\"STOPPED\"}")));

        assertThat(feedback.standing(taskWithPullRequest())).isEqualTo(FeedbackStanding.NONE);
        verifyNoInteractions(actions);
    }

    @Test
    void aTaskWithoutAPullRequestDoesNotLookAtItsRun() {
        assertThat(feedback.standing(task(null))).isEqualTo(FeedbackStanding.NONE);
        verifyNoInteractions(executions, artifactStore, actions);
    }

    private void givenRun(ExecutionStatus status, String stateJson, FeedbackActions.Action... offered) {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution(FeedbackArtifacts.VERSION, status)));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(stateArtifact(stateJson)));
        var state = FeedbackArtifacts.JSON.readValue(stateJson, FeedbackState.class);
        when(actions.view(EXECUTION_ID)).thenReturn(new FeedbackActions.View(state, 1, null, List.of(offered), null));
    }

    private static PipelineExecution execution(String version, ExecutionStatus status) {
        var execution = new PipelineExecution("dev-factory", version, "{}");
        ReflectionTestUtils.setField(execution, "id", EXECUTION_ID);
        execution.setStatus(status);
        return execution;
    }

    private static Artifact stateArtifact(String json) {
        return new Artifact(EXECUTION_ID, FeedbackArtifacts.STATE, 1, "application/json", json, "sha", "test");
    }

    private static TaskView taskWithPullRequest() {
        return task(PR);
    }

    private static TaskView task(String pullRequestUrl) {
        var state = pullRequestUrl == null ? TaskView.TaskState.STOPPED : TaskView.TaskState.PR_READY;
        var attempt = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, pullRequestUrl == null ? TaskAttempt.State.STOPPED
                : TaskAttempt.State.PR_READY, null, null, pullRequestUrl, null, EXECUTION_ID,
                Instant.parse("2026-11-10T17:48:00Z"), Instant.parse("2026-11-10T17:48:00Z"), true, 600, usage(),
                null);
        return new TaskView(UUID.randomUUID(), "MODBAR-1", "https://jira.example.org/browse/MODBAR-1", "Fix the bar",
                "MODBAR", "Open", state, null, null, null, null, "mod-bar", pullRequestUrl, null, true, false, 1,
                "input", "destination", List.of(attempt), usage(), new TaskView.AllowedActions(false, false));
    }

    private static TaskUsage usage() {
        return new TaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false, List.of());
    }
}
