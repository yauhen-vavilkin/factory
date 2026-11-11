package org.folio.factory.app.web;

import org.folio.factory.app.tasks.presentation.AttemptStatus;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.tasks.presentation.Turn;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The status card of the run page: its chip, headline, button and whose turn it is. */
class RunStatusCardTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final String STOPPED_ON = "Starting build hit a network/download failure";
    private static final RunFacts.PullRequest NO_PULL_REQUEST = new RunFacts.PullRequest("", false);

    @Test
    void aLiveRunOfNoTaskIsFactorysTurn() {
        var in = input(facts("RUNNING", RunFacts.ReasonKind.STOP, "Implement changes"), ExecutionStatus.RUNNING);

        assertThat(turn(in)).isEqualTo(Turn.WORKING);
    }

    @Test
    void aFinishedRunOfNoTaskNeedsNothingFromYou() {
        var in = input(facts("DELIVERED", RunFacts.ReasonKind.STOP, null), ExecutionStatus.COMPLETED);

        assertThat(turn(in)).isEqualTo(Turn.NONE);
    }

    @Test
    void anAttemptThatCanStartTheNextOneIsYourTurn() {
        var failed = new AttemptStatus("Failed", "chip chip-danger", null, "Attempt 1 failed");
        var in = withContext(context(failed, null, "/tasks/" + TASK_ID + "#retry"), ExecutionStatus.COMPLETED);

        assertThat(turn(in)).isEqualTo(Turn.YOURS);
    }

    @Test
    void anAttemptThatNeedsInspectionIsYourTurn() {
        var held = new AttemptStatus("Needs inspection", "chip chip-warning", null,
                "Factory could not confirm how the run ended");
        var in = withContext(context(held, null, null), ExecutionStatus.RUNNING);

        assertThat(turn(in)).isEqualTo(Turn.YOURS);
    }

    @Test
    void prFeedbackThatWaitsForYouIsYourTurnEvenWhileTheRunIsLive() {
        var summary = new FeedbackUi.Summary("Waiting for your review", true, false, true);
        var in = new RunStatusCard.Input(facts("AWAITING_HITL", RunFacts.ReasonKind.STOP, null), Optional.empty(),
                execution(ExecutionStatus.RUNNING), Optional.of(summary), noUsage(), null, null);

        assertThat(turn(in)).isEqualTo(Turn.YOURS);
    }

    @Test
    void aQuestionThatWaitsForItsAnswerOffersTheTaskPageAndIsYourTurn() {
        var asked = new AttemptStatus("Needs your answer", "chip chip-warning", null,
                "The agent stopped with a question");
        var in = withContext(context(asked, "/tasks/" + TASK_ID + "#answer", null), ExecutionStatus.COMPLETED);

        var status = RunStatusCard.status(in);

        assertThat(status.actionLabel()).isEqualTo("Answer on the task page");
        assertThat(status.actionUrl()).isEqualTo("/tasks/" + TASK_ID + "#answer");
        assertThat(RunStatusCard.turn(in, status)).isEqualTo(Turn.YOURS);
    }

    @Test
    void aRunThatWaitsForAReviewOffersItAndIsYourTurn() {
        var review = new HitlReview(EXECUTION_ID, "qa-gate-1", 2, "{}");
        var in = new RunStatusCard.Input(facts("AWAITING_HITL", RunFacts.ReasonKind.STOP, null), Optional.empty(),
                execution(ExecutionStatus.AWAITING_HITL), Optional.empty(), noUsage(), null, review);

        var status = RunStatusCard.status(in);

        assertThat(status.actionLabel()).isEqualTo("Open review");
        assertThat(status.actionUrl()).isEqualTo("/reviews/" + review.getId());
        assertThat(status.headline()).isEqualTo("Waiting for your review");
        assertThat(RunStatusCard.turn(in, status)).isEqualTo(Turn.YOURS);
    }

    @Test
    void theReviewOfPrFeedbackHasNoOpenReviewButton() {
        var review = new HitlReview(EXECUTION_ID, "pr-feedback", 8, "{}");
        var summary = new FeedbackUi.Summary("Waiting for your review", true, false, false);
        var in = new RunStatusCard.Input(facts("AWAITING_HITL", RunFacts.ReasonKind.STOP, null), Optional.empty(),
                execution(ExecutionStatus.AWAITING_HITL), Optional.of(summary), noUsage(), null, review);

        var status = RunStatusCard.status(in);

        assertThat(status.actionLabel()).isNull();
        assertThat(status.actionUrl()).isNull();
    }

    @Test
    void aLiveStepWithARetryPendingSaysWhatItWillRetryAfter() {
        var in = input(facts("RUNNING", RunFacts.ReasonKind.RETRY_PENDING, "Implement changes"),
                ExecutionStatus.RUNNING);

        var status = RunStatusCard.status(in);

        assertThat(status.headline()).isEqualTo("Implementing changes");
        assertThat(status.reasonLabel()).isEqualTo("Retry pending");
        assertThat(status.reason()).isEqualTo(STOPPED_ON + ".");
    }

    @Test
    void aLiveStepThatRetriesSaysWhatItRetriesAfter() {
        var in = input(facts("RUNNING", RunFacts.ReasonKind.RETRYING, "Verify changes"), ExecutionStatus.RUNNING);

        var status = RunStatusCard.status(in);

        assertThat(status.headline()).isEqualTo("Verifying changes");
        assertThat(status.reasonLabel()).isEqualTo("Retrying after");
        assertThat(status.reason()).isEqualTo(STOPPED_ON + ".");
    }

    @Test
    void aFinishedRunThatFailedSaysSoWithItsReason() {
        var in = input(facts("DEVELOPMENT_FAILED", RunFacts.ReasonKind.STOP, null), ExecutionStatus.COMPLETED);

        var status = RunStatusCard.status(in);

        assertThat(status.chipLabel()).isEqualTo("Failed");
        assertThat(status.headline()).isEqualTo("This run failed");
        assertThat(status.reasonLabel()).isEqualTo("Reason");
        assertThat(status.reason()).isEqualTo(STOPPED_ON + ".");
    }

    @Test
    void aFinishedRunWithAPullRequestNamesIt() {
        var pullRequest = new RunFacts.PullRequest("https://github.com/user/repo/pull/42", false);
        var facts = new RunFacts("DELIVERED", new RunFacts.Task("", "", ""), "", "", RunFacts.ReasonKind.STOP,
                pullRequest, null, new RunSteps(List.of(), null), null);
        var in = input(facts, ExecutionStatus.COMPLETED);

        var status = RunStatusCard.status(in);

        assertThat(status.chipLabel()).isEqualTo("PR #42");
        assertThat(status.chipUrl()).isEqualTo("https://github.com/user/repo/pull/42");
        assertThat(status.headline()).isEqualTo("Pull request #42 is ready for review");
    }

    @ParameterizedTest
    @CsvSource({
            "RUNNING, In progress", "PENDING, In progress", "AWAITING_HITL, Waiting for review",
            "NEEDS_DECISION, Asked a question", "DELIVERED, Finished", "COMPLETED, Finished",
            "VERIFIED, Verified", "CANCELLED, Stopped", "REJECTED, Stopped", "DEVELOPMENT_FAILED, Failed"})
    void theProductStatusOfARunOfNoTaskIsAChipInWords(String productStatus, String label) {
        assertThat(RunStatusCard.chip(productStatus, NO_PULL_REQUEST).label()).isEqualTo(label);
    }

    @Test
    void aDraftPullRequestIsADraftChipThatLinksIt() {
        var draft = new RunFacts.PullRequest("https://github.com/folio-org/mgr-applications/pull/412", true);

        var chip = RunStatusCard.chip("DELIVERED", draft);

        assertThat(chip.label()).isEqualTo("Draft PR #412");
        assertThat(chip.url()).isEqualTo(draft.url());
    }

    private static Turn turn(RunStatusCard.Input in) {
        return RunStatusCard.turn(in, RunStatusCard.status(in));
    }

    private static RunStatusCard.Input input(RunFacts facts, ExecutionStatus status) {
        return new RunStatusCard.Input(facts, Optional.empty(), execution(status), Optional.empty(), noUsage(),
                null, null);
    }

    private static RunStatusCard.Input withContext(RunContext context, ExecutionStatus status) {
        return new RunStatusCard.Input(facts("RUNNING", RunFacts.ReasonKind.STOP, null), Optional.of(context),
                execution(status), Optional.empty(), noUsage(), null, null);
    }

    private static RunFacts facts(String productStatus, RunFacts.ReasonKind kind, String currentStep) {
        return new RunFacts(productStatus, new RunFacts.Task("", "", ""), "", STOPPED_ON, kind, NO_PULL_REQUEST,
                null, new RunSteps(List.of(), currentStep), null);
    }

    private static RunContext context(AttemptStatus status, String answerUrl, String retryUrl) {
        return new RunContext(TASK_ID, "MODBAR-1", "Fix the bar", "https://jira.example.org/browse/MODBAR-1", 1,
                "glm-5.3 · max", status, null, null, answerUrl, retryUrl == null ? null : "Start attempt 2",
                retryUrl, null, false, "mod-bar", null);
    }

    private static PipelineExecution execution(ExecutionStatus status) {
        var execution = new PipelineExecution("dev-factory", "0.9.0", "{}");
        ReflectionTestUtils.setField(execution, "id", EXECUTION_ID);
        execution.setStatus(status);
        return execution;
    }

    private static RunUsage noUsage() {
        return new RunUsage(new DeveloperRunView.Usage("Usage", List.of(), null, null, null), null, false, List.of());
    }
}
