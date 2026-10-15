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
    void keepsMachineReadableStdoutSeparateFromDiagnostics() {
        var result = Processes.run(null,
                List.of("/bin/sh", "-c", "printf 'tree-sha'; printf 'diagnostic' >&2"), 10);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("tree-sha");
        assertThat(result.error()).isEqualTo("diagnostic");
    }
}
