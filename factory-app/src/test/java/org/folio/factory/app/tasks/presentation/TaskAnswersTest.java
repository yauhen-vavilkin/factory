package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAnswersTest {

    private static final UUID EXECUTION_ID = UUID.randomUUID();
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final TaskAnswers answers = new TaskAnswers(artifacts);

    @Test void theAgentsLongReportIsCutButTheAnswerIsKept() {
        storeOutcome("x".repeat(ModelCatalog.MAX_INSTRUCTIONS + 500));

        var instructions = answers.instructions(asked(), "0", "");

        assertThat(instructions).startsWith("My answer to your question: Yes\n\nYour question was: Keep it?")
                .endsWith("\n[cut to fit]")
                .hasSize(ModelCatalog.MAX_INSTRUCTIONS);
    }

    @Test void myOwnAnswerIsTheTextAndAnOptionOutOfRangeIsRefused() {
        storeOutcome("");

        assertThat(answers.instructions(asked(), TaskAnswers.OWN_ANSWER, " Only the new routes. "))
                .isEqualTo("My answer to your question: Only the new routes.\n\nYour question was: Keep it?");
        assertThatThrownBy(() -> answers.instructions(asked(), "5", null))
                .hasMessage("Choose one of the answers, then start the attempt again.");
    }

    @Test void aTaskThatDidNotAskHasNoQuestion() {
        storeOutcome("");
        var asked = asked();
        var failed = new TaskView(asked.taskId(), asked.displayKey(), asked.jiraUrl(), asked.title(),
                asked.projectKey(), asked.jiraStatus(), asked.state(), null, "verification: VERIFICATION_FAILED: x",
                null, null, null, null, null, true, false, 1, "in", "dest", asked.attempts(), asked.usage(),
                asked.allowedActions());

        assertThat(answers.question(failed)).isEmpty();
        assertThat(answers.question(asked())).isPresent();
    }

    private void storeOutcome(String textBefore) {
        var outcome = CodingOutcome.needsDecision(new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS, "Keep it?", List.of("Yes", "No"), "Both work"),
                textBefore, Map.of());
        var json = JsonMapper.builder().build().writeValueAsString(outcome);
        when(artifacts.getLatest(EXECUTION_ID, DevelopWorker.OUTCOME)).thenReturn(Optional.of(
                new Artifact(EXECUTION_ID, DevelopWorker.OUTCOME, 1, "application/json", json, "sha", "dev-develop")));
    }

    private static TaskView asked() {
        var reason = "clarify-implementation: " + TaskStops.DECISION_REASON + "Keep it?";
        var usage = new TaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false, List.of());
        var attempt = new TaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, TaskAttempt.Kind.INITIAL,
                TaskAttempt.AdmittedVia.MANUAL, TaskAttempt.State.STOPPED, "clarify-implementation", reason, null,
                null, EXECUTION_ID, Instant.now(), Instant.now(), true, 60, usage, null);
        return new TaskView(UUID.randomUUID(), "KEY-1", "https://jira.example.org/browse/KEY-1", "Title", "KEY",
                "Open", TaskView.TaskState.STOPPED, null, reason, "clarify-implementation", null, null, null, null,
                true, false, 1, "in", "dest", List.of(attempt), usage, new TaskView.AllowedActions(false, true));
    }
}
