package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessesTest {
    @Test
    void observesLinesBeforeExitAndObserverFailureDoesNotChangeResult() throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var process = executor.submit(() -> Processes.run(null,
                    List.of("/bin/sh", "-c", "printf 'first\\n'; sleep 2; printf 'last'"), 10,
                    java.util.Map.of(), 1024, line -> { received.countDown(); throw new IllegalArgumentException(); }));
            assertThat(received.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.isDone()).isFalse();
            assertThat(process.get().output()).isEqualTo("first\nlast");
        } finally { executor.shutdownNow(); }
    }

    @Test
    void observationPreservesOutputLimitAndTimeout() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Processes.run(null,
                List.of("/bin/sh", "-c", "printf '123456789'"), 10, java.util.Map.of(), 4, line -> { }))
                .hasMessageContaining("output limit");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Processes.run(null,
                List.of("/bin/sh", "-c", "exec sleep 3"), 1, java.util.Map.of(), 100, line -> { }))
                .hasMessageContaining("exceeded 1 seconds");
    }
    @Test
    void streamGivesLinesAsTheyArriveWithoutATotalOutputLimit() throws Exception {
        var first = new java.util.concurrent.CountDownLatch(1);
        var lines = new java.util.concurrent.atomic.AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // About 3 MB, more than the 2 MB total limit of run().
            var streamed = executor.submit(() -> Processes.stream(null, List.of("/bin/sh", "-c",
                    "printf 'first\\n'; sleep 1; i=0; while [ $i -lt 30000 ]; do printf '%0100d\\n' $i; i=$((i+1)); done;"
                            + " printf 'last'"), 30, s -> s, line -> {
                        first.countDown();
                        lines.incrementAndGet();
                        return true;
                    }));
            assertThat(first.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(streamed.isDone()).isFalse();
            var result = streamed.get();
            assertThat(result.exitCode()).isZero();
            assertThat(result.timedOut()).isFalse();
            assertThat(result.lineTooLarge()).isFalse();
            assertThat(lines.get()).isEqualTo(30002);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void streamStopsOnALineOverTheLimitOrWhenTheReaderSaysStop() {
        var seen = new java.util.ArrayList<String>();
        var tooLarge = Processes.stream(null, List.of("/bin/sh", "-c",
                "printf 'before\\n'; head -c " + (Processes.STREAM_LINE_LIMIT + 1)
                        + " /dev/zero | tr '\\0' x; printf '\\nafter\\n'; exec sleep 20"), 30, s -> s, line -> {
                    seen.add(line);
                    return true;
                });
        assertThat(tooLarge.lineTooLarge()).isTrue();
        assertThat(tooLarge.timedOut()).isFalse();
        assertThat(seen).containsExactly("before");

        long started = System.nanoTime();
        var stopped = Processes.stream(null, List.of("/bin/sh", "-c", "printf 'one\\ntwo\\n'; exec sleep 20"),
                30, s -> s, line -> !line.equals("two"));
        assertThat(stopped.exitCode()).isNotZero();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(10));
    }

    @Test
    void streamWaitsForASlowReaderBeforeItReturns() {
        var seen = new java.util.ArrayList<String>();
        var result = Processes.stream(null, List.of("/bin/sh", "-c", "printf 'first\\nlast\\n'"), 30, s -> s,
                line -> {
                    if (line.equals("last")) {
                        try { Thread.sleep(6000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    seen.add(line);
                    return true;
                });
        assertThat(result.outputUnread()).isFalse();
        assertThat(seen).containsExactly("first", "last");
    }

    @Test
    void streamReportsTheTimeLimitAndKeepsOnlyARedactedStderrTail() {
        var timedOut = Processes.stream(null, List.of("/bin/sh", "-c", "exec sleep 5"), 1, s -> s, line -> true);
        assertThat(timedOut.timedOut()).isTrue();

        var result = Processes.stream(null, List.of("/bin/sh", "-c",
                "i=0; while [ $i -lt 2000 ]; do printf 'noise %0100d\\n' $i >&2; i=$((i+1)); done;"
                        + " printf 'fatal: secret-key rejected' >&2; exit 3"), 30,
                s -> s.replace("secret-key", "[REDACTED]"), line -> true);
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderrTail()).endsWith("fatal: [REDACTED] rejected").doesNotContain("secret-key")
                .doesNotContain("noise " + "0".repeat(100));
        assertThat(result.stderrTail().length()).isLessThanOrEqualTo(Processes.STDERR_TAIL);
    }

    @Test
    void keepsMachineReadableStdoutSeparateFromDiagnostics() {
        var result = Processes.run(null,
                List.of("/bin/sh", "-c", "printf 'tree-sha'; printf 'diagnostic' >&2"), 10);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("tree-sha");
        assertThat(result.error()).isEqualTo("diagnostic");
    }
}
