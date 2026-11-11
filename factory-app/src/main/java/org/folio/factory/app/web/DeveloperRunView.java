package org.folio.factory.app.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything the run page of a Developer Flow attempt prints, as the mockups order it: header, status card, the
 * line on where the attempt stands, four step cards, Activity and Usage, and Technical details.
 * {@code standing} is null when the page has nothing to say there.
 */
public record DeveloperRunView(Header header, Status status, Standing standing, List<Step> steps,
                               Activity activity, Usage usage, Technical technical, Links links) {

    /**
     * Breadcrumb, h1 and meta line. {@code taskId} and {@code jiraUrl} are null for a run that no task attempt
     * started; {@code key}, {@code repository} and {@code settings} are null when the run does not know them.
     */
    public record Header(String pageTitle, UUID taskId, String key, String jiraUrl, String crumb, String title,
                         String repository, String settings) {
    }

    /**
     * Chip and headline with the lines under them, then the amount, the time and the button of the next step.
     * Every field but the chip and the headline is null when the card does not show it. {@code timeStart} makes
     * the time tick while the run works.
     */
    public record Status(String chipLabel, String chipClass, String chipUrl, String headline, String reasonLabel,
                         String reason, String question, String note, String amount, String amountNote,
                         String amountTitle, String time, Instant timeStart, String timeLimit, String actionLabel,
                         String actionUrl) {
    }

    /** "Attempt 1 of 3. Latest: attempt 3, Draft PR #14." and the button that starts the next attempt. */
    public record Standing(String lead, String latestText, String latestUrl, String tail, String retryLabel,
                           String retryUrl) {
    }

    /** One step card: "2 · Implement changes", then "Working · 5 min"; the time ticks from {@code elapsedStart}. */
    public record Step(String title, StepState state, String stateLabel, String detail, Instant elapsedStart) {

        /** The tone of the card in the CSS. */
        public String tone() {
            return state.name().toLowerCase(Locale.ROOT);
        }
    }

    public enum StepState {
        DONE, CURRENT, FAILED, QUESTION, PENDING
    }

    /** The newest rows first; the page shows the first {@code VISIBLE} and the rest behind "Show N earlier". */
    public record Activity(List<ActivityRow> rows, int total, String notice) {
        static final int VISIBLE = 20;

        public int earlier() {
            return Math.max(0, rows.size() - VISIBLE);
        }

        /** The row at {@code index} waits behind "Show N earlier". */
        public boolean hiddenAt(int index) {
            return index >= VISIBLE;
        }
    }

    /**
     * Time, verb, target and result of one tool call or runtime event. {@code verb} is null for an event that is
     * not a tool call; {@code code} says the target is a command or a path, not plain text.
     */
    public record ActivityRow(PageTime time, String verb, String target, boolean code, String result,
                              ActivityTone tone) {

        public String toneName() {
            return tone.name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ActivityTone {
        NORMAL, LIVE, FAILED
    }

    /** The Usage column: token rows, the estimated cost and a note when the cost may be too low or unknown. */
    public record Usage(String title, List<Figure> figures, String total, String cost, String note) {

        public boolean reported() {
            return !figures.isEmpty() || total != null;
        }
    }

    public record Figure(String label, String value) {
    }

    /**
     * Technical details: the checkout, the starting build, the checks when they ran and what was delivered. A
     * field is null when the run has no such fact.
     */
    public record Technical(String checkout, String startingCommit, String buildCommand, String buildResult,
                            String buildOutput, String checksPlan, String checksCommand, String checksExitCode,
                            String deliveredRepository, String deliveredBranch, String deliveredCommit) {
    }

    /** The pricing sources and the links line: Transcript, Audit log and the artifacts of the run. */
    public record Links(List<String> pricing, String transcriptUrl, String auditUrl, int artifactCount,
                        List<ArtifactGroup> artifacts) {
    }

    public record ArtifactGroup(String name, List<ArtifactLink> versions) {
    }

    public record ArtifactLink(String label, String url) {
    }
}
