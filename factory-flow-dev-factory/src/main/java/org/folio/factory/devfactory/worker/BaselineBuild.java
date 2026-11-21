package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Executes the trusted starting build and reports its diagnostic evidence. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class BaselineBuild {
    private final DevRuntimeProperties runtime;

    /** {@code install} RESOLVE lets a yarn module without a tracked lockfile generate one, which is then pinned. */
    Result run(
            DockerWorkloads.Workload baseline,
            String base,
            DevFactoryProperties.Repository repository,
            InstallMode install,
            int timeoutSeconds,
            Consumer<Map<String, Object>> progress) {
        var command = runtime.verificationCommand(repository.verificationPlan(), install);
        var toolchain = runtime.toolchain(repository.verificationPlan());
        var result = execute(baseline, command, toolchain, timeoutSeconds, progress);
        String summary = toolchain.failureSummary(result.diagnostics()).orElse("");
        var readiness = readiness(result, base, repository, command, summary);
        if (install != InstallMode.RESOLVE || result.exitCode() != 0) {
            return withLockfile(new Result(result, readiness, summary, Optional.empty(), null), toolchain, install);
        }
        try {
            var lockfile = baseline.readLockfile();
            return withLockfile(new Result(result, readiness, summary, lockfile, null), toolchain, install);
        } catch (IllegalStateException e) {
            return withLockfile(new Result(result, readiness, summary, Optional.empty(), e.getMessage()),
                    toolchain, install);
        }
    }

    private static Processes.Result execute(DockerWorkloads.Workload baseline, List<String> command,
                                            Toolchain toolchain, int timeoutSeconds,
                                            Consumer<Map<String, Object>> progress) {
        var observer = toolchain.output(
                line -> progress.accept(Map.of("activity", "baseline_progress", "message", line)));
        var heartbeat =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("factory-maven-progress-", 0).factory());
        try {
            heartbeat.scheduleAtFixedRate(observer::heartbeat, 15, 30, TimeUnit.SECONDS);
            // A verbose suite is not a failure, so only the tail of its output is kept.
            return baseline.executeKeepingTail(command, timeoutSeconds, Processes.OUTPUT_LIMIT, observer);
        } finally {
            observer.close();
            heartbeat.shutdownNow();
        }
    }

    private static Map<String, Object> readiness(Processes.Result result, String base,
                                                 DevFactoryProperties.Repository repository, List<String> command,
                                                 String summary) {
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
        return Map.copyOf(details);
    }

    /** A yarn readiness artifact says whether the lockfile was tracked, pinned here, or missing. */
    private static Result withLockfile(Result result, Toolchain toolchain, InstallMode install) {
        if (toolchain != Toolchain.YARN) {
            return result;
        }
        var details = new LinkedHashMap<>(result.readiness());
        details.put("lockfile", install == InstallMode.FROZEN ? "TRACKED"
                : result.lockfile().isPresent() ? "PINNED" : "NONE");
        result.lockfile().ifPresent(pinned -> details.put("lockfileSha256", pinned.sha256()));
        return new Result(result.process(), Map.copyOf(details), result.summary(), result.lockfile(),
                result.lockfileError());
    }

    /** {@code lockfile} is the one this starting build generated; {@code lockfileError} says why it was unusable. */
    record Result(Processes.Result process, Map<String, Object> readiness, String summary,
                  Optional<PinnedLockfile> lockfile, String lockfileError) {}

    private static String tail(String value) {
        return value.substring(Math.max(0, value.length() - MavenBaselineOutput.KEPT_TAIL));
    }
}
