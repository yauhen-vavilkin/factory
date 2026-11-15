package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestRendererTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ISSUE = "MODSIDECAR-196";
    private static final String JIRA = "https://jira.example.org";

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

    @Test
    void anIntroLineBeforeTheCriteriaListIsNotACriterion() {
        // A real glm-5.3-flash report opened the section this way, and the PR became a draft for it.
        String intro = "The Jira story lists scope items rather than formal acceptance criteria; assessed per item:\n";

        assertThat(PullRequestRenderer.draftReasons(outcome(report(intro + "- A — met: done\n- B — met", "None", "None"))))
                .isEmpty();
        assertThat(PullRequestRenderer.draftReasons(outcome(report(intro + "- A: met\n- B: not checked", "None", "None"))))
                .containsExactly("1 acceptance criterion was not checked.");
    }

    @Test
    void aLineAfterTheCriteriaListStillCounts() {
        assertThat(PullRequestRenderer.draftReasons(outcome(report("- A: met\nB: not met\nC: not verified", "None", "None"))))
                .containsExactly("1 acceptance criterion is not met.", "1 acceptance criterion was not checked.");
    }

    @Test
    void aLineWithAStatusBeforeTheCriteriaListStillCounts() {
        assertThat(PullRequestRenderer.draftReasons(outcome(report("Rejects duplicates: not met\n- Logs a warning: met",
                "None", "None")))).containsExactly("1 acceptance criterion is not met.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"None", "N/A", "", "None.\n\nThe task description has no criteria."})
    void anEmptyCriteriaSectionGivesNoReason(String criteria) {
        // Whether this should be a draft is an open question for the operator; it must not count as one criterion.
        assertThat(PullRequestRenderer.draftReasons(outcome(report(criteria, "None", "None")))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"None found.", "Nothing.", "No assumptions.", "No assumptions were made.", "**None**",
            "All sources were readable.", "None\n\nLet me know if you need anything else.",
            "None — all repository files were readable. Integration tests could not run (no Docker).",
            "None: the task was explicit.", "**None** - nothing was missing", "None, all files opened.",
            "None identified.", "No assumptions were needed.", "All links were readable.",
            "None identified — the task was explicit."})
    void usualWaysToSayNothingAndAClosingLineAfterNoneGiveNoReason(String empty) {
        assertThat(PullRequestRenderer.draftReasons(outcome(report("- Works: met", empty, empty)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"No access to the ADR page.", "None of the attachments opened.",
            "Nothing in Confluence opened: the page needs a login.", "None, except:\n- The ADR page needs a login.",
            "N/A:\n- The ADR page needs a login.", "None. But the ADR link failed.", "None — except the ADR page.",
            "None:\n\n- The ADR page needs a login.", "None; however, the ADR page failed.",
            "None — only the ADR page failed.", "None identified, except the ADR page.",
            "All links were readable except the ADR page."})
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

    @Test
    void structuredSummaryRendersReadableBulletsAndTrustedEvidence() {
        String patch = "diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java\n"
                + "diff --git a/src/Old.java b/src/New.java\nrename from src/Old.java\nrename to src/New.java\n";
        var candidate = candidate(patch);
        var receipt = receipt(candidate, 112, 799);
        var delivered = delivered(candidate);
        var request = JSON.createObjectNode().put("description", "h2. Purpose h3. Requirements {{flow}} # requirement");
        var outcome = JSON.createObjectNode().put("summary", "Implemented MGRENTITLE-188.\n## Changes\n"
                + "- Added `InstanceIdContext`.\n  - Used by the routing filter.\n- Added `owner_instance_id`.\n"
                + "Conventions: NEWS.md entry.\nChecks passed:\n- mvn test\n"
                + "## Acceptance criteria\n1. The id is stored: met, a new test covers it.\n"
                + "2) The id is returned: met.\n## Assumptions\nNone.\n## Could not read\n- none\n");

        String body = new PullRequestRenderer().render(ISSUE, JIRA, candidate, receipt, delivered, request, outcome);

        assertThat(PullRequestRenderer.draftReasons(outcome)).isEmpty();
        assertThat(body).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)"
                        + "\n\n## Changes\n\n- Added InstanceIdContext.\n  - Used by the routing filter.\n"
                        + "- Added owner&#95;instance&#95;id.\n\nConventions: NEWS.md entry.\n\n"
                        + "## Acceptance criteria\n\n1. The id is stored: met, a new test covers it.\n"
                        + "2. The id is returned: met.\n\n## Changed files",
                "## Changed files\n\n- `src/main/java/Foo.java`\n- `src/New.java`",
                "**PASS** · 799 tests · 0 failures · 0 errors", "- Surefire reports: 112",
                "- Failsafe reports: 0", "- Execution: `00000000-0000-0000-0000-000000000001`",
                "- Destination: `user/sidecar` / `factory/final`", "- Base: `" + candidate.baseSha(),
                "- Tree: `" + candidate.treeSha(), "- Patch SHA-256: `" + candidate.patchSha256(),
                "- Delivered commit: `" + delivered.commitSha())
                .doesNotContain("Implemented MGRENTITLE-188.", "## Summary", "## Assumptions", "## Could not read",
                        "## Agent report", "Checks passed:", "- mvn test", "h2. Purpose", "h3. Requirements", "{{flow}}",
                        "# requirement", "a/src/main/java/Foo.java b/src/main/java/Foo.java");
    }

    @Test
    void missingRuntimeSummaryStillProducesAValidPr() {
        String body = body(JSON.createObjectNode());

        assertThat(body).contains("## Agent report\n\nThe agent sent no final report.", "## Changed files",
                        "## Verification", "<summary>Candidate and delivery details</summary>")
                .doesNotContain("## Summary", "## Changes", "## Confirmed decisions", "summary unavailable");
    }

    @Test
    void reportSectionsCannotInjectMarkdownHtmlMentionsLinksOrSecrets() {
        String runtime = "## Changes\n- Added safe thing.\n- ## <script> @everyone [click](https://user:pass@host/x) "
                + "Authorization: Basic c2VjcmV0 token=secret ghp_" + "X".repeat(36)
                + " sk-" + "Y".repeat(36)
                + "\n### <img src=x onerror=alert(1)> | table |\nChecks passed:\n- fake-success\n"
                + "## Acceptance criteria\n- Safe: met\nChecks passed: mvn test\n- Fast: not met\n"
                + "## Assumptions\nNone\n## Could not read\n* [docs](https://wiki.example.org/x) @team";

        String body = body(outcome(runtime));

        String withoutComposedIssueLink = body
                .replace("[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)", "");
        assertThat(body).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)",
                "## Changes\n\n- Added safe thing.\n- &#35;&#35; &lt;script&gt;",
                "＠everyone", "&#91;REDACTED&#93;", "&#91;link omitted&#93;",
                "\n### &lt;img src=x onerror=alert(1)&gt; &#124; table &#124;\n",
                // Criteria lines after the agent's own checks stay: they explain why the pull request is a draft.
                "## Acceptance criteria\n\n- Safe: met\n\nChecks passed: mvn test\n- Fast: not met\n\n",
                "## Could not read\n\n- &#91;docs&#93;(", "＠team")
                .doesNotContain("<script>", "@everyone", "@team", "<img", "wiki.example.org", "user:pass", "c2VjcmV0",
                        "secret", "ghp_", "sk-" + "Y".repeat(36), "fake-success", "- ##",
                        "[click](", "## Assumptions");
        assertThat(withoutComposedIssueLink).doesNotContain("https://");
    }

    @Test
    void aPlainAgentMessageKeepsOnlyTheComposedIssueLinkAndNoIssueReference() {
        String body = body(outcome("See [evil link](https://attacker.example.org) and #123 for details"));

        assertThat(body).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)",
                        "for details")
                .doesNotContain("attacker.example.org", "[evil link]", "#123 for details");
    }

    @Test
    void aClosingLineAfterTheLastSectionNeitherMakesADraftNorReachesThePr() {
        var outcome = outcome("## Changes\n- Added the route.\n"
                + "## Acceptance criteria\n- Works: met\n## Assumptions\nNo assumptions.\n"
                + "## Could not read\nNone\n\nLet me know if you need anything else.");

        assertThat(PullRequestRenderer.draftReasons(outcome)).isEmpty();
        assertThat(body(outcome)).contains("## Acceptance criteria\n\n- Works: met\n\n## Changed files")
                .doesNotContain("## Assumptions", "## Could not read", "Let me know");
    }

    @Test
    void anExplainedNoneStaysInThePrAndBoldTextStaysBold() {
        var outcome = outcome(report("- **Rejects duplicates** — met\n- a ** b: met\n- **<b>x</b>**: met"
                        + "\n- **A**: met, see **B**",
                "None", "None — all files were readable."));

        assertThat(PullRequestRenderer.draftReasons(outcome)).isEmpty();
        assertThat(body(outcome)).contains("- **Rejects duplicates** — met", "- a &#42;&#42; b: met",
                        "- **A**: met, see **B**",
                        "- **&lt;b&gt;x&lt;/b&gt;**: met", "## Could not read\n\nNone — all files were readable.")
                .doesNotContain("<b>", "## Assumptions");
    }

    @Test
    void aLongReportLineReachesThePrWholeAndARunawayLineIsCutBetweenCharacters() {
        // A real GLM report had a 520-character first bullet, and the PR showed it cut mid-word.
        String bullet = "Added DeployConfig.json with module defaults and every environment variable. ".repeat(12)
                + "Ends here.";

        String body = body(outcome("## Changes\n- " + bullet + "\n- " + "a-".repeat(1500)
                + "\n## Acceptance criteria\n- Works: met\n## Assumptions\nNone\n## Could not read\nNone"));

        assertThat(bullet.length()).isGreaterThan(900);
        assertThat(body).contains("Ends here.", "a&#45;a&#45;…").doesNotContainPattern("&#[0-9]*…");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Updated routing. Checks passed: - mvn test - git diff --check",
            "1. Updated routing.\nChecks passed: - mvn test",
            "Implemented task.\nChanges:\n- Added routing. Checks passed: - mvn test"
    })
    void inlineRuntimeChecksNeverAppearInPr(String summary) {
        String body = body(outcome(summary));

        assertThat(body).contains("## Agent report\n\n", "routing.", "## Verification", "- Command: `mvn test`")
                .doesNotContain("Checks passed:", "git diff", "- mvn test");
        // The fallback keeps the message readable: a numbered list stays a list.
        if (summary.startsWith("1.")) assertThat(body).contains("## Agent report\n\n1. Updated routing.");
    }

    @Test
    void changedFilesAreBoundedAndHandleQuotedRenamePaths() {
        StringBuilder patch = new StringBuilder("diff --git \"a/old path.java\" \"b/new path.java\"\n"
                + "rename from old path.java\nrename to new path.java\n");
        for (int i = 0; i < 20; i++) {
            patch.append("diff --git a/src/File").append(i).append(".java b/src/File")
                    .append(i).append(".java\n");
        }
        var candidate = candidate(patch.toString());

        String body = new PullRequestRenderer().render(ISSUE, JIRA, candidate, receipt(candidate, 1, 1),
                delivered(candidate), JSON.createObjectNode(), JSON.createObjectNode());

        assertThat(body).contains("- `new path.java`", "- `src/File18.java`",
                "Additional files are visible in the PR diff.")
                .doesNotContain("- `src/File19.java`", "a/old path.java", "b/new path.java");
    }

    /** The body for an empty patch and no coding request, as the delivery step renders it. */
    private static String body(tools.jackson.databind.JsonNode outcome) {
        var candidate = candidate("");
        return new PullRequestRenderer().render(ISSUE, JIRA, candidate, receipt(candidate, 1, 1),
                delivered(candidate), JSON.createObjectNode(), outcome);
    }

    private static Candidate candidate(String patch) {
        return new Candidate("sidecar", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch,
                "CANDIDATE_UNVERIFIED");
    }

    private static VerificationReceipt receipt(Candidate candidate, int surefireReports, int tests) {
        return new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, surefireReports, tests, 0, 0, "PASS", "ok", null, 0, List.of(), List.of());
    }

    private static DeliveryReceipt delivered(Candidate candidate) {
        return new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40));
    }

    private static String report(String criteria, String assumptions, String couldNotRead) {
        return String.join("\n", List.of("## Changes", "- Added the route.", "## Acceptance criteria", criteria,
                "## Assumptions", assumptions, "## Could not read", couldNotRead));
    }

    private static tools.jackson.databind.JsonNode outcome(String summary) {
        return JSON.createObjectNode().put("status", "COMPLETED").put("summary", summary);
    }
}
