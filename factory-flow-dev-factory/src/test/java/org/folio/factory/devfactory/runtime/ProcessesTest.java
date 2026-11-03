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
    void keepingTheTailConsumesOutputBeyondTheLimitAndLeavesTheExitStatusAuthoritative() {
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        var passed = Processes.runKeepingTail(null, List.of("/bin/sh", "-c",
                "i=0; while [ $i -lt 5000 ]; do echo \"line $i\"; echo \"warn $i\" >&2; i=$((i+1)); done"),
                30, java.util.Map.of(), 1000, line -> seen.incrementAndGet());
        var failed = Processes.runKeepingTail(null, List.of("/bin/sh", "-c",
                "i=0; while [ $i -lt 5000 ]; do echo \"line $i\"; i=$((i+1)); done; exit 3"),
                30, java.util.Map.of(), 1000, null);

        assertThat(passed.exitCode()).isZero();
        assertThat(passed.output()).hasSize(1000).endsWith("line 4999\n");
        assertThat(passed.error()).hasSize(1000).endsWith("warn 4999\n");
        assertThat(seen.get()).isEqualTo(5000);
        assertThat(failed.exitCode()).isEqualTo(3);
        assertThat(failed.output()).endsWith("line 4999\n");
    }

    @Test
    void keepingTheTailStillBoundsTime() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Processes.runKeepingTail(null,
                List.of("/bin/sh", "-c", "exec sleep 3"), 1, java.util.Map.of(), 100, null))
                .hasMessageContaining("exceeded 1 seconds");
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
