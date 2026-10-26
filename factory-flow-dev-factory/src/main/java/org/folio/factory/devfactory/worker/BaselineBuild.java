package org.folio.factory.devfactory.worker;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Executes the trusted starting build and reports its diagnostic evidence. */
final class BaselineBuild {
    private final DevRuntimeProperties runtime;

    BaselineBuild(DevRuntimeProperties runtime) {
        this.runtime = runtime;
    }

    Result run(
            DockerWorkloads.Workload baseline,
            String base,
            DevFactoryProperties.Repository repository,
            InstallMode install,
            Consumer<Map<String, Object>> progress) {
        var command = runtime.verificationCommand(repository.verificationPlan(), install);
        var toolchain = runtime.toolchain(repository.verificationPlan());
        var observer =
                toolchain.output(
                        line ->
                                progress.accept(
                                        Map.of("activity", "baseline_progress", "message", line)));
        var heartbeat =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("factory-maven-progress-", 0).factory());
        Processes.Result result;
        try {
            heartbeat.scheduleAtFixedRate(observer::heartbeat, 15, 30, TimeUnit.SECONDS);
            result =
                    baseline.executeKeepingTail(
                            command, runtime.timeoutSeconds(), Processes.OUTPUT_LIMIT, observer);
        } finally {
            observer.close();
            heartbeat.shutdownNow();
        }
        String summary = toolchain.failureSummary(result.diagnostics()).orElse("");
        var details = new LinkedHashMap<String, Object>();
        details.put("state", result.exitCode() == 0 ? "BASELINE_PASSED" : "BASELINE_FAILED");
        details.put("baseSha", base);
        details.put("image", repository.buildImage());
        details.put("plan", repository.verificationPlan());
        details.put("command", command);
        details.put("exitCode", result.exitCode());
        if (!summary.isBlank()) details.put("summary", summary);
        // Jest reports to stderr, so a failed run is only explained by both streams.
        details.put("output", tail(MavenBaselineOutput.sanitize(
                result.exitCode() == 0 ? result.output() : result.diagnostics())));

        Optional<PinnedLockfile> lockfile = Optional.empty();
        String lockfileError = null;
        if (install == InstallMode.RESOLVE && result.exitCode() == 0) {
            try {
                lockfile = baseline.readLockfile();
            } catch (IllegalStateException e) {
                lockfileError = e.getMessage();
            }
        }
        if (toolchain == Toolchain.YARN) {
            details.put("lockfile", install == InstallMode.FROZEN ? "TRACKED" : lockfile.isPresent() ? "PINNED" : "NONE");
            lockfile.ifPresent(pinned -> details.put("lockfileSha256", pinned.sha256()));
        }

        return new Result(result, Map.copyOf(details), summary, lockfile, lockfileError);
    }

    /** {@code lockfile} is the one this starting build generated; {@code lockfileError} says why it was unusable. */
    record Result(Processes.Result process, Map<String, Object> readiness, String summary,
                  Optional<PinnedLockfile> lockfile, String lockfileError) {}

    private static String tail(String value) {
        return value.substring(Math.max(0, value.length() - 16000));
    }
}
