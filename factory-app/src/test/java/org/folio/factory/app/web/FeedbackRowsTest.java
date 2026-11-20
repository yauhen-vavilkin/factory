package org.folio.factory.app.web;

import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackRowsTest {

    private static final String COMMENT = "{\"id\":\"inline:1\",\"author\":\"reviewer\",\"body\":\"Add minProperties\","
            + "\"path\":\"a.json\",\"line\":3,\"outdated\":false,\"updatedAt\":\"\",\"url\":\"\"}";

    @Test
    void failedChecksSayHowManyTestsFailedAndKeepTheirOutput() {
        var round = round("{\"round\":1,\"phase\":\"FAILURE\",\"selected\":[" + COMMENT + "],"
                + "\"summary\":\"## Changes\\n- Added minProperties.\",\"verification\":"
                + checks(1, 300, 2, "FAIL", "AppTest failed") + "}");

        assertThat(round.title()).isEqualTo("Round 1");
        assertThat(round.summary()).isEqualTo("Added minProperties.");
        assertThat(round.checks()).isEqualTo("Checks failed: 2 of 300 tests failed.");
        assertThat(round.checksOutput()).isEqualTo("AppTest failed");
        assertThat(round.selected()).containsExactly(new FeedbackRows.Selected("reviewer", "Add minProperties"));
    }

    @Test
    void aJestSuiteThatFailedToLoadCountsAsAFailedTestInTheMetaLineAsInTheChecks() {
        var round = round("{\"round\":1,\"phase\":\"FAILURE\",\"selected\":[" + COMMENT + "],\"verification\":"
                + checks(1, 2, 0, 1, "FAIL", "Cannot find module") + "}");

        assertThat(round.meta()).isEqualTo("2 tests, 1 failed");
        assertThat(round.checks()).isEqualTo("Checks failed: 1 of 2 tests failed.");
    }

    @Test
    void passedChecksShowTheTestCountWithoutOutput() {
        var round = round("{\"round\":1,\"phase\":\"READY\",\"selected\":[" + COMMENT + "],\"verification\":"
                + checks(0, 1, 0, "PASS", "BUILD SUCCESS") + "}");

        assertThat(round.checks()).isEqualTo("Checks passed: 1 test.");
        assertThat(round.checksOutput()).isNull();
        // A report without a Changes section has no first sentence: the page shows nothing rather than a guess.
        assertThat(round.summary()).isNull();
        assertThat(round.report()).isNull();
    }

    @Test
    void factorysOwnSummaryOfARoundIsNotShownAsTheAgentsReport() {
        var round = round("{\"round\":1,\"phase\":\"FINISHED\",\"selected\":[" + COMMENT + "],"
                + "\"summary\":\"Verified changes published to the same PR\"}");

        assertThat(round.summary()).isNull();
        assertThat(round.report()).isNull();
        assertThat(round.selected()).hasSize(1);
    }

    @Test
    void aManualRoundHasNoAgentReportAndNoChecksBeforeTheyRan() {
        var round = round("{\"round\":1,\"phase\":\"VERIFY\",\"manual\":true,"
                + "\"summary\":\"Manual changes independently verified\"}");

        assertThat(round.title()).isEqualTo("Manual verification 1");
        assertThat(round.comments()).isEqualTo("your changes");
        assertThat(round.summary()).isNull();
        assertThat(round.report()).isNull();
        assertThat(round.checks()).isNull();
        assertThat(round.cost()).isEqualTo("$0.00");
    }

    @Test
    void theStartsOfTheRoundsCarryTheTitlesOfTheRunPage() {
        var rounds = new TreeMap<Integer, FeedbackState>();
        rounds.put(1, FeedbackArtifacts.JSON.readValue("{\"round\":1,\"phase\":\"READY\",\"manual\":true,"
                + "\"roundStartedAt\":\"2026-11-10T21:00:00Z\"}", FeedbackState.class));
        rounds.put(2, FeedbackArtifacts.JSON.readValue("{\"round\":2,\"phase\":\"READY\","
                + "\"roundStartedAt\":\"2026-11-10T21:10:00Z\"}", FeedbackState.class));

        // A manual round has no coding run, so it gets no section; the agent's round is still "Round 1".
        assertThat(FeedbackRows.starts(rounds)).containsExactly(
                new FeedbackRows.RoundStart("Round 1", java.time.Instant.parse("2026-11-10T21:10:00Z")));
    }

    private static FeedbackRows.Round round(String state) {
        var rounds = new TreeMap<Integer, FeedbackState>();
        rounds.put(1, FeedbackArtifacts.JSON.readValue(state, FeedbackState.class));
        var facts = new FeedbackRows.RoundFacts(new AttemptUsageSummary.Projection(List.of()), false, Map.of());
        return FeedbackRows.rounds(rounds, facts).getFirst();
    }

    private static String checks(int exitCode, int tests, int failures, String result, String output) {
        return checks(exitCode, tests, failures, 0, result, output);
    }

    private static String checks(int exitCode, int tests, int failures, int errors, String result, String output) {
        return "{\"executionId\":\"run-1\",\"argv\":[],\"exitCode\":" + exitCode + ",\"surefireReportCount\":1,"
                + "\"testCount\":" + tests + ",\"failureCount\":" + failures + ",\"errorCount\":" + errors + ","
                + "\"result\":\"" + result + "\",\"output\":\"" + output + "\",\"failsafeReportCount\":0}";
    }
}
