package org.folio.factory.devfactory.worker;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.runtime.Processes;

import java.util.LinkedHashMap;
import java.util.Map;
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
            int timeoutSeconds,
            Consumer<Map<String, Object>> progress) {
        var command = runtime.command(repository.verificationPlan());
        var observer =
                new MavenBaselineOutput(
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
                    baseline.execute(
                            command, timeoutSeconds, Processes.OUTPUT_LIMIT, observer);
        } finally {
            observer.close();
            heartbeat.shutdownNow();
        }
        String summary = MavenBaselineOutput.failureSummary(result.diagnostics()).orElse("");
        var details = new LinkedHashMap<String, Object>();
        details.put("state", result.exitCode() == 0 ? "BASELINE_PASSED" : "BASELINE_FAILED");
        details.put("baseSha", base);
        details.put("image", repository.buildImage());
        details.put("plan", repository.verificationPlan());
        details.put("command", command);
        details.put("exitCode", result.exitCode());
        if (!summary.isBlank()) details.put("summary", summary);
        details.put("output", tail(MavenBaselineOutput.sanitize(result.output())));

        return new Result(result, Map.copyOf(details), summary);
    }

    record Result(Processes.Result process, Map<String, Object> readiness, String summary) {}

    private static String tail(String value) {
        return value.substring(Math.max(0, value.length() - 16000));
    }
}
