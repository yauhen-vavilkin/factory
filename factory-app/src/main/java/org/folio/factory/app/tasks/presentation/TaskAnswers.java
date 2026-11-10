package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * The agent's question on the task page, and the instructions of the next attempt that answers it.
 * Factory never resumes a coding session, so the next attempt gets the answer, the question and
 * the agent's own text before the question as its instructions.
 */
@Component
@RequiredArgsConstructor
public class TaskAnswers {

    /** The value of the answer choice that takes the text field as the answer. */
    public static final String OWN_ANSWER = "own";
    private static final String CUT = "\n[cut to fit]";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ArtifactStore artifacts;

    /** The question of the latest attempt, when the task stopped with one and its outcome can be read. */
    public Optional<AgentQuestion> question(TaskView view) {
        if (!view.needsAnswer() || view.attempts().getLast().executionId() == null) {
            return Optional.empty();
        }
        return artifacts.getLatest(view.attempts().getLast().executionId(), DevelopWorker.OUTCOME)
                .map(artifact -> JSON.readValue(artifact.getContent(), CodingOutcome.class))
                .filter(outcome -> outcome.status() == CodingOutcome.Status.NEEDS_DECISION)
                .map(TaskAnswers::toQuestion);
    }

    /**
     * Instructions for the next attempt; the answer comes first, so the attempt row shows it. Throws
     * IllegalArgumentException with a message that says what to fix.
     */
    public String instructions(TaskView view, String choice, String note) {
        var question = question(view).orElseThrow(() -> new IllegalArgumentException(
                "This task has no open question any more. Reload the page."));
        var text = note == null ? "" : note.strip();
        var answer = answer(question, choice, text);
        var extra = OWN_ANSWER.equals(choice) || text.isEmpty() ? "" : "\n" + text;
        var head = "My answer to your question: " + answer + extra
                + "\n\nYour question was: " + question.question();
        return head + textBefore(question.textBefore(), ModelCatalog.MAX_INSTRUCTIONS - head.length());
    }

    private static String answer(AgentQuestion question, String choice, String text) {
        if (OWN_ANSWER.equals(choice)) {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Write your answer in the text field, or choose one of "
                        + "the agent's options.");
            }
            return text;
        }
        try {
            return question.options().get(Integer.parseInt(choice == null ? "" : choice));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Choose one of the answers, then start the attempt again.");
        }
    }

    /** The agent's report gives way when the instructions would be too long; the answer never does. */
    private static String textBefore(String text, int room) {
        var label = "\n\nWhat you wrote before the question in the last attempt:\n";
        if (text.isBlank() || room <= label.length() + CUT.length()) {
            return "";
        }
        var fits = text.length() <= room - label.length();
        return label + (fits ? text : text.substring(0, room - label.length() - CUT.length()) + CUT);
    }

    private static AgentQuestion toQuestion(CodingOutcome outcome) {
        var decision = outcome.decision();
        return new AgentQuestion(decision.question(), decision.evidence(), decision.options(), outcome.summary());
    }
}
