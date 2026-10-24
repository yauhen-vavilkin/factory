package org.folio.factory.app.pilot.startup;

import org.folio.factory.app.developer.DeveloperPreflightProbe;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The bounded runtime check behind the explicit pilot startup action: one tiny
 * tool-using invocation from the actual pinned coding image, capped at five
 * turns and two minutes, in a throwaway workload with an empty workspace — no
 * task repository, no Jira writes, no publication. It proves inference, a
 * successful tool call and usable usage reporting, and records the observation
 * without persisting any model output or credential.
 */
@Component
public class PilotRuntimeSmoke {

    static final String STEP_ID = "pilot-smoke";
    private static final int MAX_REASON_LENGTH = 500;
    private static final String SMOKE_INSTRUCTIONS =
            "This is a Factory pilot startup smoke check, not a real coding task. The workspace is empty on"
                    + " purpose. Do not create, read or modify any repository files and do not initialize git."
                    + " Run exactly one Bash command: echo factory-pilot-smoke-ok"
                    + " Then finish with a one-line summary containing that command's output.";

    private final DockerWorkloads workloads;
    private final DeveloperPreflightProbe probe;
    private final DevRuntimeProperties settings;
    private final PilotStartupProperties bounds;
    private final DeveloperPricingProperties pricing;
    private final Clock clock;
    private final CodingRuntimeFactory factory;

    /** Builds the bounded runtime for one smoke run; production uses the real Claude Code adapter. */
    @FunctionalInterface
    public interface CodingRuntimeFactory {
        CodingRuntime create(DevRuntimeProperties.ClaudeCode boundedProfile);
    }

    @Autowired
    public PilotRuntimeSmoke(DockerWorkloads workloads, DeveloperPreflightProbe probe,
                             DevRuntimeProperties settings, PilotStartupProperties bounds,
                             DeveloperPricingProperties pricing) {
        this(workloads, probe, settings, bounds, pricing, Clock.systemUTC(), ClaudeCodeCodingRuntime::new);
    }

    public PilotRuntimeSmoke(DockerWorkloads workloads, DeveloperPreflightProbe probe,
                             DevRuntimeProperties settings, PilotStartupProperties bounds,
                             DeveloperPricingProperties pricing, Clock clock, CodingRuntimeFactory factory) {
        this.workloads = workloads;
        this.probe = probe;
        this.settings = settings;
        this.bounds = bounds;
        this.pricing = pricing;
        this.clock = clock;
        this.factory = factory;
    }

    public record SmokeEvidence(
            String runtimeFingerprint,
            String image,
            String profile,
            String model,
            String endpointOrigin,
            String instructionsFingerprint,
            boolean toolUseSuccess,
            boolean usageComplete,
            boolean rateKnown,
            boolean success,
            String failureReason,
            Instant checkedAt) {
    }

    /** Stable identity of the fixed smoke instructions; part of the evidence fingerprint. */
    public static String instructionsFingerprint() {
        return RuntimeFingerprint.identityOf(SMOKE_INSTRUCTIONS);
    }

    public SmokeEvidence check(RuntimeFingerprint fingerprint) {
        if (!"claude-code".equals(settings.coding().runtime())) {
            return failed(fingerprint, PilotStartupService.RUNTIME_SELECTION_BLOCKER);
        }
        try {
            settings.coding().claudeCode().requireConfigured();
        } catch (RuntimeException e) {
            return failed(fingerprint,
                    "Configure the selected coding profile: API, model, endpoint and authentication.");
        }
        if (!probe.dockerAvailable()) {
            return failed(fingerprint,
                    "Docker daemon is unavailable; start Docker and repeat the same start action.");
        }
        if (!probe.imageAvailable(fingerprint.image())) {
            return failed(fingerprint, "Coding image " + fingerprint.image() + " is missing locally;"
                    + " build it with docker compose up -d --build and repeat the same start action.");
        }

        DevRuntimeProperties.ClaudeCode boundedProfile = smokeProfile(settings.coding().claudeCode());
        DockerWorkloads.StepScope scope = null;
        DockerWorkloads.Workload workload = null;
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("factory-pilot-smoke-");
            scope = workloads.beginStep(UUID.randomUUID(), STEP_ID);
            workload = scope.createSeeded(boundedProfile.image(), workspace, settings.mavenCacheVolume(), "coding");
            List<Map<String, Object>> events = new ArrayList<>();
            CodingOutcome outcome = factory.create(boundedProfile)
                    .code(workload, smokeRequest(), (int) bounds.smokeTimeoutSeconds(), events::add);
            workload.stop();
            return evidence(fingerprint, outcome, events);
        } catch (IOException | RuntimeException e) {
            return failed(fingerprint, "The smoke invocation failed before or during the bounded model call"
                    + " (" + e.getClass().getSimpleName() + "); repeat the same start action.");
        } finally {
            if (workload != null) {
                workload.close();
            }
            if (scope != null) {
                scope.close();
            }
            cleanup(workspace);
        }
    }

    /** The production profile bounded to the smoke's turn cap; everything else stays operator-owned. */
    public DevRuntimeProperties.ClaudeCode smokeProfile(DevRuntimeProperties.ClaudeCode profile) {
        return new DevRuntimeProperties.ClaudeCode(profile.image(), profile.api(), profile.baseUrl(),
                profile.model(), profile.authentication(), profile.apiKey(), profile.authToken(),
                bounds.smokeMaxTurns(), profile.maxOutputTokens(), profile.effort());
    }

    private static CodingRequest smokeRequest() {
        return new CodingRequest("PILOT-SMOKE", "Pilot startup smoke check", SMOKE_INSTRUCTIONS,
                List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("pilot-smoke", "factory-internal/pilot-smoke", "main",
                        "0".repeat(40)),
                CodingRequest.Constraints.defaults());
    }

    private SmokeEvidence evidence(RuntimeFingerprint fingerprint, CodingOutcome outcome,
                                   List<Map<String, Object>> events) {
        boolean toolUseSuccess = toolUseSucceeded(events);
        Map<String, Object> metrics = outcome.metrics();
        boolean usageComplete = !Boolean.TRUE.equals(metrics.get("usageIncomplete"))
                && positiveOutput(metrics);
        boolean rateKnown = pricing.rateFor(fingerprint.model()).isPresent();
        boolean success = outcome.status() == CodingOutcome.Status.COMPLETED && toolUseSuccess && usageComplete;
        String reason = null;
        if (outcome.status() == CodingOutcome.Status.FAILED) {
            reason = outcome.failure().code() + ": " + outcome.failure().message();
        } else if (outcome.status() != CodingOutcome.Status.COMPLETED) {
            reason = "The smoke invocation did not complete; the runtime asked for a product decision.";
        } else if (!toolUseSuccess) {
            reason = "The smoke invocation completed without a successful tool use.";
        } else if (!usageComplete) {
            reason = "The smoke invocation returned incomplete or missing usage evidence.";
        }
        return new SmokeEvidence(fingerprint.identity(), fingerprint.image(), fingerprint.runtime(),
                fingerprint.model(), fingerprint.endpointOrigin(), fingerprint.instructionsFingerprint(),
                toolUseSuccess, usageComplete, rateKnown, success,
                reason == null ? null : bounded(reason), Instant.now(clock));
    }

    private static boolean toolUseSucceeded(List<Map<String, Object>> events) {
        return events.stream().anyMatch(event -> "tool_execution_start".equals(event.get("activity")))
                && events.stream().anyMatch(event -> "tool_execution_end".equals(event.get("activity"))
                        && Boolean.FALSE.equals(event.get("error")));
    }

    private static boolean positiveOutput(Map<String, Object> metrics) {
        return metrics.get("outputTokens") instanceof Long tokens && tokens > 0;
    }

    private SmokeEvidence failed(RuntimeFingerprint fingerprint, String reason) {
        return new SmokeEvidence(fingerprint.identity(), fingerprint.image(), fingerprint.runtime(),
                fingerprint.model(), fingerprint.endpointOrigin(), fingerprint.instructionsFingerprint(),
                false, false, pricing.rateFor(fingerprint.model()).isPresent(), false,
                bounded(reason), Instant.now(clock));
    }

    private static String bounded(String reason) {
        String clean = reason == null ? "" : reason.replaceAll("[\\r\\n]+", " ").strip();
        return clean.substring(0, Math.min(MAX_REASON_LENGTH, clean.length()));
    }

    private static void cleanup(Path workspace) {
        if (workspace == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The smoke workspace is disposable; a leftover temp directory is harmless.
                }
            });
        } catch (IOException ignored) {
            // See above: disposal is best-effort.
        }
    }
}
