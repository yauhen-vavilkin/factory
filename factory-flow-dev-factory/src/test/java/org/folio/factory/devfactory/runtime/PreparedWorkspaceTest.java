package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PreparedWorkspaceTest {
    private final DockerWorkloads.Workload workload = mock(DockerWorkloads.Workload.class);
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final DevRuntimeProperties runtime = new DevRuntimeProperties(
            Map.of("maven", List.of("mvn", "test"), "jest", List.of("yarn", "test")), null, 60, null, Map.of(),
            Map.of("jest", Toolchain.YARN));
    private final List<String> install = runtime.installCommand("jest");

    @Test
    void mavenPlansHaveNoInstallStepAndNeverTouchTheWorkload() {
        var result = PreparedWorkspace.prepare(workload, runtime, "maven", events::add);

        assertThat(result.installed()).isFalse();
        assertThat(result.failed()).isFalse();
        verifyNoInteractions(workload);
        assertThat(events).isEmpty();
    }

    @Test
    void yarnPlansRunTheConfiguredInstallOnceAndReportIt() {
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(0, "Done in 14s."));

        var result = PreparedWorkspace.prepare(workload, runtime, "jest", events::add);

        assertThat(result.installed()).isTrue();
        assertThat(result.failed()).isFalse();
        verify(workload).execute(install, 60);
        assertThat(events).extracting(e -> e.get("activity"))
                .containsExactly("workspace_install_started", "workspace_install_completed");
        assertThat(events.get(0)).containsEntry("command", String.join(" ", install));
    }

    @Test
    void resolvingModeRunsTheInstallWithoutTheFrozenFlag() {
        var resolving = runtime.installCommand("jest", InstallMode.RESOLVE);
        when(workload.execute(resolving, 60)).thenReturn(new Processes.Result(0, "Done in 14s."));

        var result = PreparedWorkspace.prepare(workload, runtime, "jest", InstallMode.RESOLVE, events::add);

        assertThat(result.installed()).isTrue();
        assertThat(resolving).isNotEqualTo(install).doesNotContain("--frozen-lockfile");
        verify(workload).execute(resolving, 60);
        assertThat(events.get(0)).containsEntry("command", String.join(" ", resolving));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ETIMEDOUT\".",
            "error An unexpected error occurred: \"https://registry.example/pkg.tgz: ESOCKETTIMEDOUT\"."})
    void registryTimeoutsAreRetryable(String output) {
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(1, output));

        var result = PreparedWorkspace.prepare(workload, runtime, "jest", events::add);

        assertThat(result.failed()).isTrue();
        assertThat(result.retryable()).isTrue();
        assertThat(result.failure()).contains("pkg.tgz").doesNotContain("Starting build");
    }

    @Test
    void otherInstallFailuresAreNotRetryableAndCarryTheYarnError() {
        when(workload.execute(install, 60)).thenReturn(new Processes.Result(1,
                "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`."));

        var result = PreparedWorkspace.prepare(workload, runtime, "jest", events::add);

        assertThat(result.failed()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(result.failure()).contains("lockfile needs to be updated").doesNotContain("Starting build");
    }

    @Test
    void aTransportFailureWithoutAnExitStatusIsAFailedInstall() {
        when(workload.execute(install, 60)).thenThrow(new IllegalStateException("docker exec failed"));

        var result = PreparedWorkspace.prepare(workload, runtime, "jest", events::add);

        assertThat(result.failed()).isTrue();
        assertThat(result.retryable()).isFalse();
    }
}
