package org.folio.factory.app.pilot;

import org.folio.factory.app.developer.DeveloperPreflightProbe;
import org.folio.factory.app.pilot.startup.PilotRuntimeSmoke;
import org.folio.factory.app.pilot.startup.PilotStartupProperties;
import org.folio.factory.app.pilot.startup.RuntimeFingerprint;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The bounded smoke contract: one tiny tool-using invocation, scripted runtimes, no real API calls. */
class PilotRuntimeSmokeTest {

    private static final DevRuntimeProperties.ClaudeCode PROFILE = new DevRuntimeProperties.ClaudeCode(
            "pinned:image", "anthropic-messages", "http://gateway.fixture.invalid:4001", "exact-model",
            "bearer-token", null, "smoke-fixture-secret", 40, 16384, null);
    private static final DevRuntimeProperties SETTINGS = new DevRuntimeProperties(
            Map.of("unit", List.of("mvn", "-B", "test")),
            new DevRuntimeProperties.Coding(null, null, null, null, null, null,
                    "claude-code", null, null, null, PROFILE), 1800, "fixture-m2-cache");
    private static final PilotStartupProperties BOUNDS = new PilotStartupProperties(null, null);
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private final DockerWorkloads workloads = mock(DockerWorkloads.class);
    private final DeveloperPreflightProbe probe = mock(DeveloperPreflightProbe.class);
    private final DockerWorkloads.StepScope scope = mock(DockerWorkloads.StepScope.class);
    private final DockerWorkloads.Workload workload = mock(DockerWorkloads.Workload.class);
    private final CodingRuntime runtime = mock(CodingRuntime.class);
    private final DeveloperPricingProperties pricing = new DeveloperPricingProperties(Map.of("local",
            new DeveloperPricingProperties.Rate("exact-model", "USD", LocalDate.of(2026, 10, 22),
                    URI.create("https://pricing.fixture.invalid"), BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE, BigDecimal.ONE)));

    private CodingOutcome answer;
    private List<Map<String, Object>> outcomeEvents = List.of();
    private RuntimeException failure;

    @BeforeEach
    void scriptedEnvironment() {
        when(probe.dockerAvailable()).thenReturn(true);
        when(probe.imageAvailable(anyString())).thenReturn(true);
        when(workloads.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createSeeded(anyString(), any(), anyString(), anyString())).thenReturn(workload);
        when(runtime.code(any(DockerWorkloads.Workload.class), any(CodingRequest.class), anyInt(), any()))
                .thenAnswer(invocation -> {
                    if (failure != null) {
                        throw failure;
                    }
                    Consumer<Map<String, Object>> progress = invocation.getArgument(3);
                    outcomeEvents.forEach(progress);
                    return answer;
                });
    }

    @Test
    void successfulSmokeRecordsIdentityToolUseAndUsageAndCleansTheWorkload() {
        succeedWith(CodingOutcome.completed("factory-pilot-smoke-ok",
                Map.of("inputTokens", 12L, "outputTokens", 5L)));

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isTrue();
        assertThat(evidence.toolUseSuccess()).isTrue();
        assertThat(evidence.usageComplete()).isTrue();
        assertThat(evidence.rateKnown()).isTrue();
        assertThat(evidence.failureReason()).isNull();
        assertThat(evidence.image()).isEqualTo("pinned:image");
        assertThat(evidence.profile()).isEqualTo("claude-code");
        assertThat(evidence.model()).isEqualTo("exact-model");
        assertThat(evidence.endpointOrigin()).isEqualTo("http://gateway.fixture.invalid:4001");
        assertThat(evidence.instructionsFingerprint())
                .isEqualTo(PilotRuntimeSmoke.instructionsFingerprint());
        assertThat(evidence.runtimeFingerprint()).isEqualTo(fingerprint().identity());
        assertThat(evidence.checkedAt()).isEqualTo(NOW);
        verify(runtime).code(eq(workload), any(CodingRequest.class), eq(120), any());
        verify(workload).stop();
        verify(workload).close();
        verify(scope).close();
    }

    @Test
    void theSmokeRuntimeIsBoundedToTheConfiguredSmokeTurns() {
        succeedWith(CodingOutcome.completed("done", Map.of("inputTokens", 12L, "outputTokens", 5L)));

        var bounded = smoke().smokeProfile(PROFILE);

        assertThat(bounded.maxTurns()).isEqualTo(5);
        assertThat(bounded.image()).isEqualTo(PROFILE.image());
        assertThat(bounded.model()).isEqualTo(PROFILE.model());
        assertThat(bounded.baseUrl()).isEqualTo(PROFILE.baseUrl());
        assertThat(bounded.secret()).isEqualTo(PROFILE.secret());
        assertThat(bounded.maxOutputTokens()).isEqualTo(PROFILE.maxOutputTokens());
    }

    @Test
    void unavailableDockerFailsFastWithoutInvokingTheRuntime() {
        when(probe.dockerAvailable()).thenReturn(false);

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("Docker daemon is unavailable");
        verifyNoInteractions(runtime, workloads);
    }

    @Test
    void missingImageNamesTheConcreteBuildCommand() {
        when(probe.imageAvailable("pinned:image")).thenReturn(false);

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("pinned:image", "docker compose up -d --build");
        verifyNoInteractions(runtime);
    }

    @Test
    void unconfiguredProfileFailsBeforeAnyProbeOrModelUse() {
        var unconfigured = new DevRuntimeProperties(
                Map.of(), new DevRuntimeProperties.Coding(null, null, null, null, null, null,
                "claude-code", null, null, null,
                new DevRuntimeProperties.ClaudeCode(null, "openai-completions", null, null,
                        null, null, null, null, null, null)), 1800, null);

        var evidence = smoke(unconfigured).check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("Configure the selected coding profile");
        verifyNoInteractions(probe, runtime, workloads);
    }

    @Test
    void aNonClaudeCodeSelectionIsNotSmokeTested() {
        var pi = new DevRuntimeProperties(
                Map.of(), new DevRuntimeProperties.Coding(null, null, null, null, null, null,
                "pi", null, null, null, PROFILE), 1800, null);

        var evidence = smoke(pi).check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("claude-code coding runtime");
        verifyNoInteractions(probe, runtime, workloads);
    }

    @Test
    void completedAnswerWithoutAToolUseIsNotSuccess() {
        succeedWith(CodingOutcome.completed("no tool ran", Map.of("inputTokens", 9L, "outputTokens", 4L)),
                List.of());

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.toolUseSuccess()).isFalse();
        assertThat(evidence.usageComplete()).isTrue();
        assertThat(evidence.failureReason()).contains("without a successful tool use");
    }

    @Test
    void erroredToolResultIsNotASuccessfulToolUse() {
        succeedWith(CodingOutcome.completed("tool failed", Map.of("inputTokens", 9L, "outputTokens", 4L)),
                List.of(Map.of("activity", "tool_execution_start", "tool", "Bash"),
                        Map.of("activity", "tool_execution_end", "error", true)));

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.toolUseSuccess()).isFalse();
    }

    @Test
    void incompleteUsageIsNotSuccess() {
        succeedWith(CodingOutcome.completed("done", Map.of("inputTokens", 0L, "outputTokens", 0L,
                "usageIncomplete", true)));

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.toolUseSuccess()).isTrue();
        assertThat(evidence.usageComplete()).isFalse();
        assertThat(evidence.failureReason()).contains("usage");
    }

    @Test
    void zeroOutputTokensAreIncompleteUsage() {
        succeedWith(CodingOutcome.completed("done", Map.of("inputTokens", 9L, "outputTokens", 0L)));

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.usageComplete()).isFalse();
        assertThat(evidence.success()).isFalse();
    }

    @Test
    void failedOutcomeCarriesTheBoundedFailureReason() {
        answer = CodingOutcome.failed("PROVIDER_FAILED", "The provider rejected the request", Map.of());

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).startsWith("PROVIDER_FAILED:");
        assertThat(evidence.runtimeFingerprint()).isEqualTo(fingerprint().identity());
    }

    @Test
    void unexpectedRuntimeFailuresAreReportedWithoutLeakingSecretsOrDiagnostics() {
        answer = CodingOutcome.completed("unused", Map.of());
        failure = new IllegalStateException("boom with smoke-fixture-secret inside");

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("repeat the same start action");
        assertThat(evidence.failureReason()).doesNotContain("smoke-fixture-secret", "boom");
        verify(workload).close();
    }

    @Test
    void missingRateCardIsRecordedWithoutBlockingTheSmokeObservation() {
        succeedWith(CodingOutcome.completed("done", Map.of("inputTokens", 9L, "outputTokens", 4L)));
        var smoke = new PilotRuntimeSmoke(workloads, probe, SETTINGS, BOUNDS,
                new DeveloperPricingProperties(Map.of()), fixedClock(), profile -> runtime);

        var evidence = smoke.check(fingerprint());

        assertThat(evidence.success()).isTrue();
        assertThat(evidence.rateKnown()).isFalse();
    }

    @Test
    void instructionsFingerprintIsStableHexAndWorkloadCreationFailureFailsClosed() {
        assertThat(PilotRuntimeSmoke.instructionsFingerprint()).matches("[0-9a-f]{64}");
        assertThat(PilotRuntimeSmoke.instructionsFingerprint())
                .isEqualTo(PilotRuntimeSmoke.instructionsFingerprint());

        answer = CodingOutcome.completed("unused", Map.of());
        when(scope.createSeeded(anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("docker create failed"));

        var evidence = smoke().check(fingerprint());

        assertThat(evidence.success()).isFalse();
        assertThat(evidence.failureReason()).contains("repeat the same start action");
        verify(runtime, never()).code(any(), any(), anyInt(), any());
        verify(scope).close();
    }

    @Test
    void theRuntimeIsAlwaysInvokedWithTheConfiguredTimeBound() {
        succeedWith(CodingOutcome.completed("done", Map.of("inputTokens", 9L, "outputTokens", 4L)));
        var bounded = new PilotStartupProperties(null, Duration.ofSeconds(90));

        new PilotRuntimeSmoke(workloads, probe, SETTINGS, bounded, pricing, fixedClock(),
                profile -> runtime).check(fingerprint());

        verify(runtime).code(any(), any(), eq(90), any());
        verify(runtime, never()).code(any(), any(), anyInt());
    }

    private void succeedWith(CodingOutcome outcome) {
        succeedWith(outcome, List.of(
                Map.of("activity", "tool_execution_start", "tool", "Bash"),
                Map.of("activity", "tool_execution_end", "error", false)));
    }

    private void succeedWith(CodingOutcome outcome, List<Map<String, Object>> events) {
        this.answer = outcome;
        this.outcomeEvents = events;
    }

    private PilotRuntimeSmoke smoke() {
        return smoke(SETTINGS);
    }

    private PilotRuntimeSmoke smoke(DevRuntimeProperties settings) {
        return new PilotRuntimeSmoke(workloads, probe, settings, BOUNDS, pricing, fixedClock(),
                profile -> runtime);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private RuntimeFingerprint fingerprint() {
        return RuntimeFingerprint.of(PROFILE, BOUNDS.smokeMaxTurns(),
                (int) BOUNDS.smokeTimeoutSeconds(), PilotRuntimeSmoke.instructionsFingerprint());
    }
}
