package org.folio.factory.app.pilot;

import org.folio.factory.app.StubLlmConfiguration;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.execution.PilotDispatchPoller;
import org.folio.factory.app.pilot.execution.PilotDispatcher;
import org.folio.factory.app.pilot.execution.PilotExecutionProperties;
import org.folio.factory.app.pilot.persistence.PilotAttempt;
import org.folio.factory.app.pilot.persistence.PilotAttemptRepository;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidence;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidenceRepository;
import org.folio.factory.app.pilot.persistence.PilotTask;
import org.folio.factory.app.pilot.persistence.PilotTaskRepository;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.app.pilot.startup.PilotReadiness;
import org.folio.factory.app.pilot.startup.PilotReadinessGate;
import org.folio.factory.app.pilot.startup.PilotRuntimeIdentity;
import org.folio.factory.app.pilot.startup.PilotRuntimeSmoke;
import org.folio.factory.app.pilot.startup.PilotStartupProperties;
import org.folio.factory.app.pilot.startup.PilotStartupService;
import org.folio.factory.app.pilot.startup.RuntimeFingerprint;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one explicit startup action against real PostgreSQL: a fresh setup runs
 * the disclosed bounded smoke once, an unchanged restart reuses the evidence
 * with zero new smoke runs, affected configuration or a shared outage re-runs
 * the same start action, and queued pilot work waits for applicable readiness
 * while S05's sync and ownership checks stay authoritative.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.enabled=false",
        "factory.limits.dedup.window=PT0S",
        "factory.pilot.execution.enabled=true",
        "factory.pilot.execution.dispatch-interval-ms=3600000",
        "factory.pilot.source.source=981",
        "factory.pilot.source.sprint-id=4711",
        "factory.pilot.source.statuses[0]=To Do"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PilotStartupTest {

    private static final String INSTANCE = "jira.example.org";
    private static final String AUTHORITY = "authority-1";
    private static final String FLOW_ID = "dev-factory";
    private static final String NOT_RUN = PilotStartupService.NOT_RUN_BLOCKER;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @MockitoBean
    DeveloperPreflight preflight;

    @MockitoBean
    PilotRuntimeSmoke smoke;

    @MockitoBean
    DeveloperPricingProperties pricing;

    @MockitoBean
    RetentionProperties retention;

    @Autowired
    PilotStartupService startup;

    @Autowired
    PilotRuntimeIdentity identity;

    @Autowired
    PilotReadinessGate gate;

    @Autowired
    PilotReadinessEvidenceRepository evidence;

    @Autowired
    PilotTaskRepository tasks;

    @Autowired
    PilotAttemptRepository attempts;

    @Autowired
    PilotInstallationRepository installations;

    @Autowired
    PilotAdmissionService admissions;

    @Autowired
    PilotDispatcher dispatcher;

    @Autowired
    PilotDispatchPoller dispatchPoller;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    StateManager stateManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DevRuntimeProperties settings;

    @Autowired
    PilotStartupProperties startupProperties;

    @Autowired
    PilotExecutionProperties executionProperties;

    @Autowired
    PilotSourceProperties sourceProperties;

    @BeforeEach
    void resetPilotState() {
        attempts.deleteAll();
        tasks.deleteAll();
        installations.deleteAll();
        executions.deleteAll();
        evidence.deleteAll();
    }

    @Test
    void freshStartRunsOneBoundedSmokeAndEstablishesFullReadiness() {
        smokePasses();
        ratesKnown();
        syncDiscovered();

        PilotReadiness readiness = startup.start();

        assertThat(readiness.executionReady()).isTrue();
        assertThat(readiness.discoveryAvailable()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
        assertThat(readiness.evidenceCurrent()).isTrue();
        assertThat(readiness.evidenceFingerprint()).isEqualTo(identity.current().identity());
        assertThat(readiness.smokeDisclosure()).contains("one bounded smoke", "no task repository");
        assertThat(readiness.smoke().success()).isTrue();
        verify(smoke).check(any(RuntimeFingerprint.class));

        assertThat(evidence.count()).isEqualTo(1);
        PilotReadinessEvidence row = evidence.findAll().getFirst();
        assertThat(row.isPassed()).isTrue();
        assertThat(row.getRuntimeFingerprint()).isEqualTo(identity.current().identity());
        assertThat(row.getFingerprint()).contains("credentialIdentity").doesNotContain("codemie-proxy");
        assertThat(installations.findById(1).orElseThrow().getRuntimeReadinessRef())
                .isEqualTo(row.getId().toString());
    }

    @Test
    void failedPrerequisiteCarriesAConcreteCorrection() {
        smokeFails("Docker daemon is unavailable; start Docker and repeat the same start action.");
        ratesKnown();
        syncDiscovered();

        PilotReadiness readiness = startup.start();

        assertThat(readiness.executionReady()).isFalse();
        assertThat(readiness.blockers()).containsExactly(
                "Docker daemon is unavailable; start Docker and repeat the same start action.");
        assertThat(evidence.findAll()).singleElement().satisfies(row -> {
            assertThat(row.isPassed()).isFalse();
            assertThat(row.getFailureReason()).contains("Docker daemon is unavailable");
        });
        assertThat(gateRefusal()).contains(NOT_RUN);
    }

    @Test
    void discoveryStaysAvailableWhileExecutionIsBlocked() {
        smokePasses();
        syncDiscovered();

        PilotReadiness readiness = startup.start();

        assertThat(readiness.discoveryAvailable()).isTrue();
        assertThat(readiness.executionReady()).isFalse();
        assertThat(readiness.blockers()).singleElement().asString().contains("No rate card for");
    }

    @Test
    void unchangedRestartReusesEvidenceWithZeroNewSmokeRuns() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        PilotReadiness first = startup.start();

        PilotReadiness second = startup.start();

        assertThat(second.executionReady()).isTrue();
        assertThat(second.evidenceFingerprint()).isEqualTo(first.evidenceFingerprint());
        assertThat(second.checkedAt()).isEqualTo(first.checkedAt());
        verify(smoke, times(1)).check(any(RuntimeFingerprint.class));
        assertThat(evidence.count()).isEqualTo(1);
    }

    @Test
    void affectedConfigurationChangeInvalidatesEvidenceAndRunsTheSmokeAgain() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        assertThat(startup.start().executionReady()).isTrue();

        var modifiedSettings = settingsWithModel("changed-model");
        var secondIdentity = new PilotRuntimeIdentity(modifiedSettings, startupProperties);
        var secondService = secondService(modifiedSettings, secondIdentity);
        // Evidence ordering is by checked_at: keep the two runs distinct instants.
        sleepBriefly();
        PilotReadiness changed = secondService.start();

        verify(smoke, times(2)).check(any(RuntimeFingerprint.class));
        assertThat(changed.executionReady()).isTrue();
        assertThat(changed.evidenceFingerprint()).isEqualTo(secondIdentity.current().identity());
        assertThat(evidence.count()).isEqualTo(2);
        assertThat(evidence.findAll()).extracting(PilotReadinessEvidence::getRuntimeFingerprint)
                .containsExactlyInAnyOrder(identity.current().identity(),
                        secondIdentity.current().identity());

        PilotReadiness status = startup.status();
        assertThat(status.evidenceCurrent()).isFalse();
        assertThat(status.blockers()).contains(PilotStartupService.STALE_BLOCKER);
        assertThat(gateRefusal()).contains("changed after the last check");
    }

    @Test
    void afterRevertingConfigurationStatusAgreesWithTheGateOnTheMatchingPassedEvidence() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        assertThat(startup.start().executionReady()).isTrue();

        var modifiedSettings = settingsWithModel("changed-model");
        var secondService = secondService(modifiedSettings,
                new PilotRuntimeIdentity(modifiedSettings, startupProperties));
        sleepBriefly();
        smokeFails("PROVIDER_FAILED: the provider rejected the smoke request");
        assertThat(secondService.start().executionReady()).isFalse();
        assertThat(evidence.count()).isEqualTo(2);

        PilotReadiness reverted = startup.status();

        verify(smoke, times(2)).check(any(RuntimeFingerprint.class));
        assertThat(reverted.executionReady()).isTrue();
        assertThat(reverted.blockers()).doesNotContain(NOT_RUN);
        assertThat(reverted.evidenceCurrent()).isTrue();
        assertThat(reverted.evidenceFingerprint()).isEqualTo(identity.current().identity());
        assertThat(gateRefusal()).isNull();
    }

    @Test
    void sharedOutageThenTheSameStartActionRecovers() {
        smokeFails("PROVIDER_FAILED: the provider rejected the smoke request");
        ratesKnown();
        syncDiscovered();

        assertThat(startup.start().executionReady()).isFalse();
        verify(smoke, times(1)).check(any(RuntimeFingerprint.class));

        smokePasses();
        PilotReadiness recovered = startup.start();

        verify(smoke, times(2)).check(any(RuntimeFingerprint.class));
        assertThat(recovered.executionReady()).isTrue();
        assertThat(evidence.count()).isEqualTo(2);
        assertThat(evidence.findTopByPassedTrueOrderByCheckedAtDesc()).isPresent();
    }

    @Test
    void statusNeverRunsTheSmokeAndReportsNotRunBeforeTheFirstStart() {
        ratesKnown();
        syncDiscovered();

        PilotReadiness before = startup.status();

        verifyNoInteractions(smoke);
        assertThat(before.executionReady()).isFalse();
        assertThat(before.blockers()).contains(NOT_RUN);
        assertThat(before.smoke()).isNull();
        assertThat(before.evidenceCurrent()).isFalse();

        smokePasses();
        assertThat(startup.start().executionReady()).isTrue();

        PilotReadiness after = startup.status();

        verify(smoke, times(1)).check(any(RuntimeFingerprint.class));
        assertThat(after.executionReady()).isTrue();
        assertThat(after.evidenceCurrent()).isTrue();
    }

    @Test
    void retentionEnabledRefusesActivationAndKeepsRecordedHistory() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        assertThat(startup.start().executionReady()).isTrue();
        when(retention.enabled()).thenReturn(true);

        PilotReadiness readiness = startup.start();

        assertThat(readiness.executionReady()).isFalse();
        assertThat(readiness.blockers()).contains(PilotStartupService.RETENTION_BLOCKER);
        assertThat(gateRefusal()).contains("FACTORY_RETENTION_ENABLED");
        assertThat(evidence.count()).isEqualTo(1);
        assertThat(evidence.findTopByPassedTrueOrderByCheckedAtDesc()).isPresent();
    }

    @Test
    void dispatchWaitsForReadinessThenProceedsAfterTheStartAction() {
        ratesKnown();
        syncDiscovered();
        UUID taskId = admit("60001", "MODBAR-601");

        assertThatThrownBy(dispatcher::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NOT_RUN);
        assertThat(executions.count()).isZero();
        assertThat(attempts.findLiveByTaskId(taskId))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED));

        smokePasses();
        assertThat(startup.start().executionReady()).isTrue();

        var executionId = dispatcher.dispatchNext();
        assertThat(executionId).isPresent();
        assertThat(stateManager.get(executionId.get()).getFlowId()).isEqualTo(FLOW_ID);
        assertThat(attempts.findByExecutionId(executionId.get()).orElseThrow().getState())
                .isEqualTo(PilotAttempt.State.QUEUED);
    }

    @Test
    void admissionSucceedsAndThePollerStaysQuietWhileReadinessIsMissing() {
        ratesKnown();
        syncDiscovered();
        UUID taskId = admit("60003", "MODBAR-603");

        dispatchPoller.poll();

        assertThat(attempts.findLiveByTaskId(taskId))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getState()).isEqualTo(PilotAttempt.State.RESERVED);
                    assertThat(attempt.getExecutionId()).isNull();
                });
        assertThat(executions.count()).isZero();
    }

    @Test
    void admissionAloneLaunchesTheQueueOnceReadinessHolds() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        assertThat(startup.start().executionReady()).isTrue();

        UUID taskId = admit("60004", "MODBAR-604");

        PilotAttempt attempt = attempts.findLiveByTaskId(taskId).getFirst();
        assertThat(attempt.getState())
                .as("admission itself dispatches best-effort; no test-side dispatchNext is involved")
                .isEqualTo(PilotAttempt.State.QUEUED);
        assertThat(attempt.getExecutionId()).isNotNull();
        assertThat(stateManager.get(attempt.getExecutionId()).getFlowId()).isEqualTo(FLOW_ID);
        assertThat(installations.findById(1).orElseThrow().getActiveAttemptId()).isEqualTo(attempt.getId());
    }

    @Test
    void staleSourceSyncStillBlocksDispatchBeforeReadinessIsConsulted() {
        syncDiscovered();
        // Admitted before any start action: the readiness refusal keeps the
        // admission's own best-effort dispatch quiet, so the attempt stays queued.
        admit("60011", "MODBAR-611");
        PilotInstallation installation = installations.findById(1).orElseThrow();
        installation.setSyncComplete(false);
        installation.setSyncProblem("search page 2 failed");
        installations.save(installation);

        assertThatThrownBy(dispatcher::dispatchNext)
                .isInstanceOf(IllegalStateException.class)
                .as("the stale sync is reported even though readiness is also missing")
                .hasMessageContaining("sync is incomplete");
        assertThat(executions.count()).isZero();
    }

    @Test
    void processingDisabledKeepsExecutionBlockedRegardlessOfEvidence() {
        smokePasses();
        ratesKnown();
        syncDiscovered();
        var disabled = new PilotExecutionProperties(executionProperties.deadline(), false);
        var service = new PilotStartupService(settings, pricing, sourceProperties, disabled,
                startupProperties, retention, smoke, identity, evidence, installations, transactionManager);

        PilotReadiness readiness = service.status();

        assertThat(readiness.executionReady()).isFalse();
        assertThat(readiness.blockers()).contains(PilotStartupService.EXECUTION_PROCESSING_BLOCKER);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PilotStartupService secondService(DevRuntimeProperties modifiedSettings,
                                              PilotRuntimeIdentity secondIdentity) {
        return new PilotStartupService(modifiedSettings, pricing, sourceProperties, executionProperties,
                startupProperties, retention, smoke, secondIdentity, evidence, installations,
                transactionManager);
    }

    private DevRuntimeProperties settingsWithModel(String model) {
        var profile = new DevRuntimeProperties.ClaudeCode(
                settings.coding().claudeCode().image(), settings.coding().claudeCode().api(),
                settings.coding().claudeCode().baseUrl(), model,
                settings.coding().claudeCode().authentication(),
                settings.coding().claudeCode().apiKey(), settings.coding().claudeCode().authToken(),
                settings.coding().claudeCode().maxTurns(),
                settings.coding().claudeCode().maxOutputTokens(), settings.coding().claudeCode().effort());
        return new DevRuntimeProperties(settings.plans(),
                new DevRuntimeProperties.Coding(null, null, null, null, null, null,
                        settings.coding().runtime(), null, null, null, profile),
                settings.timeoutSeconds(), settings.mavenCacheVolume());
    }

    private String gateRefusal() {
        try {
            gate.requireExecutionReady();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return null;
    }

    private void smokePasses() {
        when(smoke.check(any())).thenAnswer(invocation ->
                passed(stubFingerprint(invocation.getArgument(0))));
    }

    private void smokeFails(String reason) {
        when(smoke.check(any())).thenAnswer(invocation ->
                failed(stubFingerprint(invocation.getArgument(0)), reason));
    }

    private static PilotRuntimeSmoke.SmokeEvidence passed(RuntimeFingerprint fingerprint) {
        return new PilotRuntimeSmoke.SmokeEvidence(fingerprint.identity(), fingerprint.image(),
                fingerprint.runtime(), fingerprint.model(), fingerprint.endpointOrigin(),
                fingerprint.instructionsFingerprint(), true, true, true, true, null, Instant.now());
    }

    private static PilotRuntimeSmoke.SmokeEvidence failed(RuntimeFingerprint fingerprint, String reason) {
        return new PilotRuntimeSmoke.SmokeEvidence(fingerprint.identity(), fingerprint.image(),
                fingerprint.runtime(), fingerprint.model(), fingerprint.endpointOrigin(),
                fingerprint.instructionsFingerprint(), false, false, false, false, reason,
                Instant.now());
    }

    /** The matcher call during re-stubbing passes null; answer with the defaulted local profile. */
    private static RuntimeFingerprint stubFingerprint(RuntimeFingerprint fingerprint) {
        return fingerprint != null ? fingerprint
                : RuntimeFingerprint.of(new DevRuntimeProperties.ClaudeCode(null, null, null, null,
                        null, null, null, null, null, null),
                        PilotStartupProperties.DEFAULT_SMOKE_MAX_TURNS, 120,
                        PilotRuntimeSmoke.instructionsFingerprint());
    }

    private void ratesKnown() {
        when(pricing.rateFor(any())).thenReturn(Optional.of(new DeveloperPricingProperties.Rate(
                "claude-sonnet-5-5", "USD", LocalDate.of(2026, 10, 22),
                URI.create("https://pricing.fixture.invalid"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE)));
    }

    private void syncDiscovered() {
        PilotInstallation installation = installations.findById(1).orElseGet(PilotInstallation::initial);
        installation.setSyncComplete(true);
        installation.setSyncProblem(null);
        installation.setObservedAuthorityFingerprint(AUTHORITY);
        installations.save(installation);
    }

    private UUID admit(String issueId, String displayKey) {
        PilotTask task = tasks.save(new PilotTask(INSTANCE, issueId, displayKey, "Title of " + displayKey,
                "MODBAR", "{\"key\": \"" + displayKey + "\"}", "revision-" + issueId,
                PilotTask.Eligibility.ELIGIBLE, "repository mod-bar"));
        var result = admissions.runSelected(new PilotCommands.RunSelected("run-" + issueId, AUTHORITY,
                List.of(new PilotCommands.Selection(task.getId(), task.getVersion(),
                        PilotAdmissionService.inputFingerprint(task),
                        PilotAdmissionService.destinationFingerprint(task)))));
        assertThat(result.rejectedReasons()).as(String.valueOf(result)).isEmpty();
        return task.getId();
    }
}
