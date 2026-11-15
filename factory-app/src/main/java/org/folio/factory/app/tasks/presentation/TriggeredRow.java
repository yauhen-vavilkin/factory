package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.web.PageTime;
import org.folio.factory.app.web.UiFormat;

import java.util.List;
import java.util.UUID;

/**
 * One task under Started tasks: its latest attempt as a chip, cost and age, a line of facts, and the one action
 * its state allows. chipUrl is set when the chip is a pull request and opens GitHub; costTitle says why a cost may
 * be too low. facts are the model, the attempts and the time the agent worked. detail is one line (the first
 * sentence of a failure) and detailTitle its whole text. button opens the page where the developer acts, null
 * when the row has none or offers Close as failed, which is a form with markStoppedAttemptId.
 */
public record TriggeredRow(UUID taskId, String key, String jiraUrl, String title, String chipLabel,
                           String chipClass, String chipUrl, List<String> facts, String detail, String detailTitle,
                           String cost, String costTitle, String when,
                           PageTime whenAt, RunAction action, Button button, UUID markStoppedAttemptId) {

    /** A button of a row that opens a page: the answer or the next attempt on the task page, or PR feedback. */
    public record Button(String label, String url) { }

    /** The whole line of facts, for its title when a narrow row cuts it. */
    public String factsTitle() {
        return UiFormat.joinNonNull(String.join(" · ", facts), detailTitle);
    }
}
