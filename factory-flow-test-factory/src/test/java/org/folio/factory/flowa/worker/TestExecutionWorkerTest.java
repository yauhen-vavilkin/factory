package org.folio.factory.flowa.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.flowa.worker.TestExecutionWorker.MAX_CONSOLE_LOG_CHARS;

class TestExecutionWorkerTest {

    @Test
    void truncateConsole_shortOutput_returnedUnchanged() {
        String console = "Karate run finished: 3 passed, 0 failed";

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_exactlyAtLimit_returnedUnchanged() {
        String console = "x".repeat(MAX_CONSOLE_LOG_CHARS);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).isEqualTo(console);
    }

    @Test
    void truncateConsole_overLimit_keepsTailAndSaysHowMuchWasOmitted() {
        String marker = "FINAL-KARATE-SUMMARY-MARKER-XY";
        String console = "a".repeat(MAX_CONSOLE_LOG_CHARS + 500 - marker.length()) + marker;

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result).endsWith(marker);
        // Pin the full phrase: a bare contains("500") would also match the total-length
        // figure (20500) and pass with a wrong omitted-count computation.
        assertThat(result).contains("truncated")
                .contains("500 of " + console.length() + " characters omitted");
        // The input has no newlines, so the first blank line is the header/payload boundary.
        String payload = result.substring(result.indexOf("\n\n") + 2);
        assertThat(payload).isEqualTo(console.substring(console.length() - MAX_CONSOLE_LOG_CHARS));
    }

    @Test
    void truncateConsole_overLimit_boundedTotalLength() {
        String console = "b".repeat(MAX_CONSOLE_LOG_CHARS + 500);

        String result = TestExecutionWorker.truncateConsole(console);

        assertThat(result.length()).isLessThan(MAX_CONSOLE_LOG_CHARS + 200);
    }
}
