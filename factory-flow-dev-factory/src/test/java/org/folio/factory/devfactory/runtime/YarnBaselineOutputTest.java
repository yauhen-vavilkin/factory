package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class YarnBaselineOutputTest {
    @Test
    void emitsStepsAndResultsButNotTheHundredsOfPeerDependencyWarnings() {
        var events = new ArrayList<String>();
        var output = new YarnBaselineOutput(events::add);
        output.accept("yarn install v1.22.22");
        output.accept("[1/4] Resolving packages...");
        output.accept("(node:53212) [DEP0169] DeprecationWarning: `url.parse()` behavior is not standardized");
        output.accept("[2/4] Fetching packages...");
        output.accept("[3/4] Linking dependencies...");
        for (int i = 0; i < 500; i++) {
            output.accept("warning \" > react-final-form@6.5." + i + "\" has unmet peer dependency \"final-form@^4\".");
        }
        output.accept("[4/4] Building fresh packages...");
        output.accept("warning Ignored scripts due to flag.");
        output.accept("Done in 144.26s.");

        assertThat(events).containsExactly("[1/4] Resolving packages...", "[2/4] Fetching packages...",
                "[3/4] Linking dependencies...", "[4/4] Building fresh packages...", "Done in 144.26s.");
    }

    @Test
    void samplesPassingSuitesKeepsEveryFailureAndTheSummaries() {
        var events = new ArrayList<String>();
        var output = new YarnBaselineOutput(events::add);
        for (int i = 1; i <= 60; i++) output.accept("PASS src/views/Suite" + i + ".test.js (1.2 s)");
        output.accept("FAIL src/settings/OwnerSettings.test.js (11.3 s)");
        output.accept("Test Suites: 1 failed, 60 passed, 61 total");
        output.accept("Tests:       1 failed, 3 skipped, 1638 passed, 1642 total");
        output.accept("Snapshots:   0 total");

        assertThat(events).contains("PASS src/views/Suite1.test.js (1.2 s)", "PASS src/views/Suite25.test.js (1.2 s)",
                "FAIL src/settings/OwnerSettings.test.js (11.3 s)", "Test Suites: 1 failed, 60 passed, 61 total",
                "Tests:       1 failed, 3 skipped, 1638 passed, 1642 total")
                .doesNotContain("PASS src/views/Suite11.test.js (1.2 s)", "Snapshots:   0 total")
                .hasSizeLessThan(25);
    }

    @Test
    void redactsCredentialsAndStopsAtTheEventLimitOrWhenClosed() {
        var events = new ArrayList<String>();
        var output = new YarnBaselineOutput(events::add);
        output.accept("error An unexpected error occurred: \"https://alice:secret@registry.example/x.tgz: ETIMEDOUT\".");
        assertThat(events).singleElement().asString().contains("[REDACTED]").doesNotContain("secret");

        for (int i = 0; i < 400; i++) output.heartbeat();
        assertThat(events).hasSize(YarnBaselineOutput.EVENT_LIMIT);

        var closedEvents = new ArrayList<String>();
        var closed = new YarnBaselineOutput(closedEvents::add);
        closed.close();
        closed.accept("[1/4] Resolving packages...");
        closed.heartbeat();
        assertThat(closedEvents).isEmpty();
    }

    @Test
    void summarizesTheLastYarnErrorAndIgnoresTheTrailingCommandFailure() {
        String output = "yarn install v1.22.22\n[1/4] Resolving packages...\n"
                + "\u001B[31merror\u001B[39m Couldn't find package \"left-pad@^9\" required by \"app\" on the \"npm\" registry.\n"
                + "error Command failed with exit code 1.\n";

        assertThat(YarnBaselineOutput.failureSummary(output)).hasValue(
                "Starting build failed: Couldn't find package \"left-pad@^9\" required by \"app\" on the \"npm\" registry.");
        assertThat(YarnBaselineOutput.failureSummary("error Command failed with exit code 1.")).isEmpty();
        assertThat(YarnBaselineOutput.failureSummary("Tests: 1 failed")).isEmpty();
        assertThat(YarnBaselineOutput.failureSummary(null)).isEmpty();
        assertThat(YarnBaselineOutput.failureSummary("error " + "x".repeat(900)).orElseThrow()).hasSizeLessThan(260);
        assertThat(YarnBaselineOutput.failureSummary("error Authorization: Bearer abc123def").orElseThrow())
                .doesNotContain("abc123def");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ETIMEDOUT\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: getaddrinfo ENOTFOUND registry.example\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: read ECONNRESET\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: getaddrinfo EAI_AGAIN registry.example\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: socket hang up\".",
            "error https://registry.example/pkg.tgz: Request failed \"503 Service Unavailable\"",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: Request failed \\\"503 Service Unavailable\\\"\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ESOCKETTIMEDOUT\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: connect ECONNREFUSED 10.0.0.1:443\".",
            "\u001B[31merror\u001B[39m An unexpected error occurred: \"https://registry.example/pkg.tgz: ETIMEDOUT\"."})
    void retriesYarnsOwnNetworkErrors(String failure) {
        assertThat(YarnBaselineOutput.isTransientDownloadFailure("[2/4] Fetching packages...\n" + failure)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "info There appears to be trouble with your network connection. Retrying...",
            "error Couldn't find package \"left-pad@^9\" required by \"app\" on the \"npm\" registry.",
            "error https://registry.example/pkg.tgz: Request failed \"404 Not Found\"",
            "error https://registry.example/pkg.tgz: Request failed \"401 Unauthorized\"",
            "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.",
            "error Incorrect integrity when fetching from the cache",
            "error Command failed with exit code 1.",
            "  console.error\n    Error: connect ECONNRESET 127.0.0.1:443",
            "FAIL src/api.test.js\n  ● fetch retries\n    Error: getaddrinfo ENOTFOUND api.example",
            "Error: connect ETIMEDOUT while rendering"})
    void doesNotRetryPermanentErrorsRecoveredRetriesOrNetworkTextFromTests(String failure) {
        assertThat(YarnBaselineOutput.isTransientDownloadFailure("[2/4] Fetching packages...\n" + failure)).isFalse();
        assertThat(YarnBaselineOutput.isTransientDownloadFailure(null)).isFalse();
    }

    @Test
    void lastErrorHasNoStepWordingWhileTheStartingBuildSummaryKeepsIt() {
        String output = "[1/4] Resolving packages...\nerror Couldn't find package \"left-pad@^9\"\nerror Command failed with exit code 1.";

        assertThat(YarnBaselineOutput.lastError(output)).hasValueSatisfying(error ->
                assertThat(error).startsWith("Couldn't find package").doesNotContain("Starting build"));
        assertThat(YarnBaselineOutput.failureSummary(output)).hasValueSatisfying(summary ->
                assertThat(summary).startsWith("Starting build failed: Couldn't find package"));
        assertThat(YarnBaselineOutput.lastError("ok")).isEmpty();
    }
}
