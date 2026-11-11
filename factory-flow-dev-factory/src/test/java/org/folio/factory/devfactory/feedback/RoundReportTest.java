package org.folio.factory.devfactory.feedback;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RoundReportTest {

    /** The agent's report of PR feedback round 2 on MGRAPPS-21, as stored. */
    private static String report() throws IOException {
        try (var in = RoundReportTest.class.getResourceAsStream("/feedback/round-report.md")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void changesKeepOnlyTheChangesSectionAsMarkdown() throws IOException {
        var changes = RoundReport.changes(report());

        assertThat(changes).startsWith("- Added a one-line `\"description\"")
                .contains("- Repository conventions applied")
                .doesNotContain("## ", "Acceptance criteria", "Assumptions", "Could not read", "inline:");
    }

    @Test
    void firstSentenceIsPlainTextCutAtAWord() throws IOException {
        var first = RoundReport.firstSentence(report());

        assertThat(first).startsWith("Added a one-line \"description\": \"A single attribute entry")
                .doesNotContain("`", "#").hasSizeLessThanOrEqualTo(200).endsWith("…");
        var kept = first.substring(0, first.length() - 1);
        assertThat(kept).doesNotEndWith(" ");
        assertThat(RoundReport.text(report())).contains("• " + kept + " ");
    }

    @Test
    void theStoredReportAsTextHasNoMarkdownMarks() throws IOException {
        assertThat(RoundReport.text(report())).startsWith("Changes\n• Added a one-line \"description\"")
                .contains("\nAssumptions\n").doesNotContain("## ", "`", "inline:");
    }

    @Test
    void firstSentenceTakesOneSentenceOfTheChanges() {
        var report = "## Changes\n- Renamed the test. It matches the class.\n- Fixed the [link](https://x).\n\n"
                + "## Assumptions\n- None (PR feedback inline:123).";

        assertThat(RoundReport.firstSentence(report)).isEqualTo("Renamed the test.");
        assertThat(RoundReport.firstSentence("## Changes\n- Added `minProperties` (PR feedback inline:1)"))
                .isEqualTo("Added minProperties.");
        assertThat(RoundReport.changes(report)).isEqualTo("- Renamed the test. It matches the class.\n"
                + "- Fixed the [link](https://x).");
    }

    @Test
    void aReportWithoutAChangesSectionHasNoFirstSentence() {
        assertThat(RoundReport.hasChanges("Verified changes published to the same PR")).isFalse();
        assertThat(RoundReport.hasChanges("## Assumptions\n- None.")).isFalse();
        assertThat(RoundReport.hasChanges(null)).isFalse();
        assertThat(RoundReport.hasChanges("Nothing to change. ## Changes\n- None: it passes.")).isTrue();
        assertThat(RoundReport.firstSentence("Manual changes independently verified")).isEmpty();
        assertThat(RoundReport.firstSentence("## Assumptions\n- None.")).isEmpty();
        assertThat(RoundReport.firstSentence("")).isEmpty();
        assertThat(RoundReport.firstSentence(null)).isEmpty();
    }

    @Test
    void textLeavesOutMarkdownMarksAndInternalIds() {
        var report = "## Changes\n- Added `minProperties` to **items** (PR feedback inline:1).\n\n"
                + "## Assumptions\n- See [the docs](https://x).";

        assertThat(RoundReport.text(report)).isEqualTo("Changes\n• Added minProperties to items.\n\n"
                + "Assumptions\n• See the docs.");
    }

    @Test
    void aRoundWithoutChangesKeepsItsReportReadable() {
        // FeedbackWorker puts its own sentence in front of the report of a round that changed nothing.
        var report = "No code changes were needed; current code verified. ## Changes\n- None: the test already passes."
                + "\n\n## Assumptions\n- None.";

        assertThat(RoundReport.firstSentence(report)).isEqualTo("None: the test already passes.");
        assertThat(RoundReport.text(report)).isEqualTo("No code changes were needed; current code verified.\n"
                + "Changes\n• None: the test already passes.\n\nAssumptions\n• None.");
    }

    @Test
    void aReportWithoutHeadingsIsUsedAsItIsAndInternalIdsAreRemoved() {
        assertThat(RoundReport.changes("Corrected selected feedback (PR feedback inline:42).\n"))
                .isEqualTo("Corrected selected feedback.");
    }
}
