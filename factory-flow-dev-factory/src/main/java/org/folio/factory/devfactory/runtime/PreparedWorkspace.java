package org.folio.factory.devfactory.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs the plan's trusted dependency install inside a coding container before the agent
 * starts, so the agent finds the same ready workspace that verification builds on. A failed
 * install stops the attempt before any model call.
 */
public final class PreparedWorkspace {
    private PreparedWorkspace() {}

    /** {@code installed} is false when the plan has no install step; {@code failure} is null on success. */
    public record Result(boolean installed, boolean retryable, String failure) {
        public boolean failed() { return failure != null; }
    }

    public static Result prepare(DockerWorkloads.Workload workload, DevRuntimeProperties runtime, String plan,
                                 Consumer<Map<String, Object>> progress) {
        return prepare(workload, runtime, plan, InstallMode.FROZEN, progress);
    }

    public static Result prepare(DockerWorkloads.Workload workload, DevRuntimeProperties runtime, String plan,
                                 InstallMode mode, Consumer<Map<String, Object>> progress) {
        var install = runtime.installCommand(plan, mode);
        if (install.isEmpty()) return new Result(false, false, null);
        var toolchain = runtime.toolchain(plan);
        progress.accept(Map.of("activity", "workspace_install_started", "command", String.join(" ", install)));
        Instant started = Instant.now();
        Processes.Result result;
        try {
            result = workload.execute(install, runtime.timeoutSeconds());
        } catch (IllegalStateException e) {
            // Transport failure has no exit status; report it like a failed install.
            result = new Processes.Result(-1, Objects.toString(e.getMessage(), "Install failed without a diagnostic"));
        }
        progress.accept(Map.of("activity", "workspace_install_completed", "exitCode", result.exitCode(),
                "seconds", Duration.between(started, Instant.now()).toSeconds()));
        if (result.exitCode() == 0) return new Result(true, false, null);
        String failure = YarnBaselineOutput.lastError(result.diagnostics())
                .orElse("Dependency install failed with exit code " + result.exitCode());
        return new Result(false, toolchain.isTransientDownloadFailure(result.diagnostics()), failure);
    }
}
