package org.folio.factory.devfactory.worker;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaselineBuildTest {
    private final DevRuntimeProperties runtime = new DevRuntimeProperties(
            Map.of("jest", List.of("yarn", "test")), null, 60, null, Map.of(), Map.of("jest", Toolchain.YARN));
    private final DevFactoryProperties.Repository repository = new DevFactoryProperties.Repository(
            "owner/ui", "master", "node:image", "jest", List.of("T"), List.of());

    private Map<String, Object> readiness(Processes.Result result) {
        var baseline = mock(DockerWorkloads.Workload.class);
        when(baseline.executeKeepingTail(any(), anyInt(), anyInt(), any())).thenReturn(result);
        return new BaselineBuild(runtime).run(baseline, "a".repeat(40), repository, InstallMode.FROZEN, progress -> { })
                .readiness();
    }

    @Test
    void aFailedRunKeepsStderrWhereJestReportsItsFailures() {
        var details = readiness(new Processes.Result(1, "yarn run v1.22.22", "FAIL src/a.test.js\n  ● renders"));

        assertThat(details.get("state")).isEqualTo("BASELINE_FAILED");
        assertThat((String) details.get("output")).contains("yarn run v1.22.22", "FAIL src/a.test.js");
    }

    @Test
    void aPassingRunKeepsStdoutOnly() {
        var details = readiness(new Processes.Result(0, "Done in 3s.", "warning noise"));

        assertThat(details.get("state")).isEqualTo("BASELINE_PASSED");
        assertThat((String) details.get("output")).contains("Done in 3s.").doesNotContain("warning noise");
    }
}
