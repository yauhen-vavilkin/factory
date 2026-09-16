package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessesTest {
    @Test
    void keepsMachineReadableStdoutSeparateFromDiagnostics() {
        var result = Processes.run(null,
                List.of("/bin/sh", "-c", "printf 'tree-sha'; printf 'diagnostic' >&2"), 10);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("tree-sha");
        assertThat(result.error()).isEqualTo("diagnostic");
    }
}
