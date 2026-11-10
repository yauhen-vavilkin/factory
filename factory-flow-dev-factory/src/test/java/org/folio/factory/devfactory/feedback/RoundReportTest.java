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
    void briefIsPlainTextOfAtMost280Characters() throws IOException {
        var brief = RoundReport.brief(report());

        assertThat(brief).startsWith("Added a one-line \"description\": \"A single attribute entry")
                .doesNotContain("`", "#", "- ").hasSizeLessThanOrEqualTo(280).endsWith("…");
    }

    @Test
    void briefTakesAtMostTwoSentences() {
        var report = "## Changes\n- Renamed the test. It matches the class.\n- Fixed the [link](https://x).\n\n"
                + "## Assumptions\n- None (PR feedback inline:123).";

        assertThat(RoundReport.brief(report)).isEqualTo("Renamed the test. It matches the class.");
        assertThat(RoundReport.changes(report)).isEqualTo("- Renamed the test. It matches the class.\n"
                + "- Fixed the [link](https://x).");
    }

    @Test
    void aReportWithoutHeadingsIsUsedAsItIsAndInternalIdsAreRemoved() {
        assertThat(RoundReport.changes("Corrected selected feedback (PR feedback inline:42).\n"))
                .isEqualTo("Corrected selected feedback.");
        assertThat(RoundReport.brief("Corrected selected feedback")).isEqualTo("Corrected selected feedback.");
        assertThat(RoundReport.brief("")).isEmpty();
    }
}
