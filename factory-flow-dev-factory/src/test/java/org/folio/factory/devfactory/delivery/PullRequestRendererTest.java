package org.folio.factory.devfactory.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestRendererTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aReportThatConfirmsEverythingOpensAReadyPullRequest() {
        assertThat(PullRequestRenderer.draftReasons(outcome(report(
                "- Endpoint returns 404: met, covered by a new test.\n- Logs the tenant: met.", "None.", "- none"))))
                .isEmpty();
        assertThat(PullRequestRenderer.draftReasons(outcome(report("1. Works: met", "*None*", "N/A")))).isEmpty();
    }

    @Test
    void unmetOrUncheckedCriteriaAssumptionsAndUnreadSourcesMakeItADraft() {
        String criteria = "- Endpoint returns 404: NOT MET, the route is missing.\n"
                + "- Logs the tenant: not checked, no logging test.\n"
                + "- Works offline: not  checked.\n"
                + "- Builds: met.";

        assertThat(PullRequestRenderer.draftReasons(outcome(report(criteria,
                "The tenant header is always present.", "- https://wiki.example.org/x (login required)"))))
                .containsExactly("1 acceptance criterion is not met.",
                        "2 acceptance criteria were not checked.",
                        "The agent made assumptions that a reviewer should confirm.",
                        "The agent could not read some of the task's sources.");
    }

    @Test
    void aCriterionCountsAsConfirmedOnlyWhenItsStatusPlainlySaysMet() {
        String criteria = "- A: partially met, the list is not sorted.\n"
                + "- B: unmet\n"
                + "- C: not verified\n"
                + "- D: failed, the test is red.\n"
                + "- E has no status at all\n"
                + "- Retention is met for old rows: not checked\n"
                + "- F — **Met**\n"
                + "Checks passed: mvn test\n"
                + "- G: met, covered by a new test.";

        assertThat(PullRequestRenderer.draftReasons(outcome(report(criteria, "None", "None"))))
                .containsExactly("2 acceptance criteria are not met.", "4 acceptance criteria were not checked.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"None", "N/A", "", "None.\n\nThe task description has no criteria."})
    void anEmptyCriteriaSectionGivesNoReason(String criteria) {
        // Whether this should be a draft is an open question for the operator; it must not count as one criterion.
        assertThat(PullRequestRenderer.draftReasons(outcome(report(criteria, "None", "None")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"None found.", "Nothing.", "No assumptions.", "No assumptions were made.", "**None**",
            "All sources were readable.", "None\n\nLet me know if you need anything else."})
    void usualWaysToSayNothingAndAClosingLineAfterNoneGiveNoReason(String empty) {
        assertThat(PullRequestRenderer.draftReasons(outcome(report("- Works: met", empty, empty)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"No access to the ADR page.", "None of the attachments opened.",
            "Nothing in Confluence opened: the page needs a login."})
    void textThatOnlyStartsLikeNoneIsStillAReason(String couldNotRead) {
        assertThat(PullRequestRenderer.draftReasons(outcome(report("- Works: met", "None", couldNotRead))))
                .containsExactly("The agent could not read some of the task's sources.");
    }

    @Test
    void reasonsAreFactoryWordsAndNeverCopyModelText() {
        var reasons = PullRequestRenderer.draftReasons(outcome(report(
                "- <script>alert(1)</script> @everyone: not met", "<b>secret</b>", "None")));

        assertThat(String.join(" ", reasons)).doesNotContain("<", "@", "secret", "script");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Implemented the change.",
            "## Changes\n- a\n## Acceptance criteria\n- b: met\n## Assumptions\nNone",
            "## Changes\n- a\n## Changes\n- b\n## Acceptance criteria\n- c: met\n## Assumptions\nNone\n## Could not read\nNone",
            "### Changes\n- a\n### Acceptance criteria\n- b: met\n### Assumptions\nNone\n### Could not read\nNone"
    })
    void aMessageThatDoesNotFollowTheTemplateIsADraft(String message) {
        assertThat(PullRequestRenderer.draftReasons(JSON.createObjectNode().put("summary", message)))
                .containsExactly("The agent's final report does not use the four expected sections,"
                        + " so nothing in it is confirmed.");
    }

    @Test
    void sectionsAreReadFromTheWholeMessageNotOnlyTheFirst4000Characters() {
        String longChanges = ("- Changed a file with a long explanation of what happened there.\n").repeat(80);
        String message = "## Changes\n" + longChanges + "## Acceptance criteria\n- Works: met\n"
                + "## Assumptions\nNone\n## Could not read\n- The ADR page needs a login.";

        assertThat(message.length()).isGreaterThan(4000).isLessThan(8000);
        assertThat(PullRequestRenderer.reportSections(message)).containsKeys("Could not read");
        assertThat(PullRequestRenderer.draftReasons(JSON.createObjectNode().put("summary", message)))
                .containsExactly("The agent could not read some of the task's sources.");
    }

    @Test
    void headingsMayDifferInCaseOrEndWithAColon() {
        Map<String, String> sections = PullRequestRenderer.reportSections(
                "Done.\n## changes:\n- a\n##  Acceptance Criteria\n- b: met\n## ASSUMPTIONS\nNone\n## Could not read :\nNone");

        assertThat(sections).containsExactly(Map.entry("Changes", "- a"),
                Map.entry("Acceptance criteria", "- b: met"), Map.entry("Assumptions", "None"),
                Map.entry("Could not read", "None"));
    }

    private static String report(String criteria, String assumptions, String couldNotRead) {
        return String.join("\n", List.of("## Changes", "- Added the route.", "## Acceptance criteria", criteria,
                "## Assumptions", assumptions, "## Could not read", couldNotRead));
    }

    private static tools.jackson.databind.JsonNode outcome(String summary) {
        return JSON.createObjectNode().put("status", "COMPLETED").put("summary", summary);
    }
}
