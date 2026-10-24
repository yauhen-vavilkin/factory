package org.folio.factory.app.pilot.startup;

import org.folio.factory.app.pilot.execution.PilotExecutionProperties;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidence;
import org.folio.factory.app.pilot.persistence.PilotReadinessEvidenceRepository;
import org.folio.factory.app.pilot.startup.PilotRuntimeSmoke.SmokeEvidence;
import org.folio.factory.app.pilot.source.PilotSourceProperties;
import org.folio.factory.app.web.DeveloperPricingProperties;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one explicit pilot startup action. {@link #start()} rechecks every live
 * prerequisite and, unless the recorded evidence already covers the current
 * runtime fingerprint, runs the disclosed bounded smoke; {@link #status()}
 * reports the same readiness from persisted evidence and live configuration
 * without ever invoking a model. Results never carry secrets: blockers name
 * settings, fingerprints carry identity hashes only.
 */
@Service
public class PilotStartupService {

    private static final Logger log = LoggerFactory.getLogger(PilotStartupService.class);

    public static final String RETENTION_BLOCKER =
            "Pilot execution is refused: destructive retention is enabled (FACTORY_RETENTION_ENABLED);"
                    + " pilot attempts and evidence must not be purged. Disable retention and repeat the start action.";
    public static final String CONFIGURATION_BLOCKER =
            "Configure the selected coding profile: API, model, endpoint and authentication.";
    public static final String RUNTIME_SELECTION_BLOCKER =
            "The prepared pilot path requires the claude-code coding runtime"
                    + " (FACTORY_DEV_FACTORY_CODING_RUNTIME=claude-code).";
    public static final String EXECUTION_PROCESSING_BLOCKER =
            "Enable pilot execution processing (FACTORY_PILOT_EXECUTION_ENABLED=true).";
    public static final String NOT_RUN_BLOCKER = "Runtime check has not completed";
    public static final String STALE_BLOCKER =
            "The coding runtime configuration changed after the last check; repeat the start action.";
    public static final String SMOKE_FAILED_BLOCKER =
            "The runtime check failed; repeat the same start action after fixing the reported cause.";
    public static final String SOURCE_BLOCKER =
            "Configure the team source: board or saved filter, sprint and eligible statuses"
                    + " (FACTORY_PILOT_SOURCE, FACTORY_PILOT_SPRINT_ID, FACTORY_PILOT_STATUSES).";
    public static final String DISCOVERY_BLOCKER =
            "Pilot source discovery has not completed a full sync yet; discovery stays unavailable until then.";

    private final DevRuntimeProperties settings;
    private final DeveloperPricingProperties pricing;
    private final PilotSourceProperties source;
    private final PilotExecutionProperties execution;
    private final PilotStartupProperties bounds;
    private final RetentionProperties retention;
    private final PilotRuntimeSmoke smoke;
    private final PilotRuntimeIdentity identity;
    private final PilotReadinessEvidenceRepository evidence;
    private final PilotInstallationRepository installations;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public PilotStartupService(DevRuntimeProperties settings, DeveloperPricingProperties pricing,
                               PilotSourceProperties source, PilotExecutionProperties execution,
                               PilotStartupProperties bounds, RetentionProperties retention,
                               PilotRuntimeSmoke smoke, PilotRuntimeIdentity identity,
                               PilotReadinessEvidenceRepository evidence,
                               PilotInstallationRepository installations,
                               PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.pricing = pricing;
        this.source = source;
        this.execution = execution;
        this.bounds = bounds;
        this.retention = retention;
        this.smoke = smoke;
        this.identity = identity;
        this.evidence = evidence;
        this.installations = installations;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private enum EvidenceState { CURRENT, FAILED, STALE, ABSENT }

    private record Snapshot(RuntimeFingerprint fingerprint, boolean runtimeConfigured,
                            boolean claudeCodeSelected, boolean rateKnown, boolean sourceConfigured) {
    }

    /**
     * Runs the explicit startup action: live prerequisite checks plus the
     * bounded smoke whenever the persisted evidence does not already cover the
     * current runtime fingerprint. An unchanged restart reuses the evidence and
     * makes no model call.
     */
    public PilotReadiness start() {
        Snapshot snapshot = snapshot();
        Optional<PilotReadinessEvidence> passed = evidence.findTopByPassedTrueOrderByCheckedAtDesc();
        SmokeEvidence smokeEvidence;
        EvidenceState state;
        if (passed.isPresent()
                && passed.get().getRuntimeFingerprint().equals(snapshot.fingerprint().identity())) {
            smokeEvidence = smokeFrom(passed.get());
            state = EvidenceState.CURRENT;
        } else {
            smokeEvidence = runSmoke(snapshot.fingerprint());
            state = smokeEvidence.success() ? EvidenceState.CURRENT : EvidenceState.FAILED;
        }
        return assemble(snapshot, smokeEvidence, state);
    }

    /** Read-only progress and result report: no model call, no probe, no write. */
    public PilotReadiness status() {
        Snapshot snapshot = snapshot();
        Optional<PilotReadinessEvidence> latest = evidence.findTopByOrderByCheckedAtDesc();
        if (latest.isEmpty()) {
            return assemble(snapshot, null, EvidenceState.ABSENT);
        }
        Optional<PilotReadinessEvidence> passed = evidence.findTopByPassedTrueOrderByCheckedAtDesc();
        if (passed.isEmpty()) {
            return assemble(snapshot, smokeFrom(latest.get()), EvidenceState.FAILED);
        }
        PilotReadinessEvidence row = passed.get();
        EvidenceState state = row.getRuntimeFingerprint().equals(snapshot.fingerprint().identity())
                ? EvidenceState.CURRENT
                : EvidenceState.STALE;
        return assemble(snapshot, smokeFrom(row), state);
    }

    private SmokeEvidence runSmoke(RuntimeFingerprint fingerprint) {
        SmokeEvidence result;
        try {
            result = smoke.check(fingerprint);
        } catch (RuntimeException e) {
            log.warn("Pilot runtime smoke could not run ({})", e.getClass().getName());
            result = failedEvidence(fingerprint);
        }
        persist(fingerprint, result);
        return result;
    }

    private SmokeEvidence failedEvidence(RuntimeFingerprint fingerprint) {
        return new SmokeEvidence(fingerprint.identity(), fingerprint.image(), fingerprint.runtime(),
                fingerprint.model(), fingerprint.endpointOrigin(), fingerprint.instructionsFingerprint(),
                false, false, snapshot().rateKnown(), false, SMOKE_FAILED_BLOCKER, Instant.now());
    }

    private void persist(RuntimeFingerprint fingerprint, SmokeEvidence result) {
        PilotReadiness readiness = assemble(snapshot(), result,
                result.success() ? EvidenceState.CURRENT : EvidenceState.FAILED);
        transactions.executeWithoutResult(status -> {
            PilotReadinessEvidence row = new PilotReadinessEvidence(UUID.randomUUID(),
                    fingerprint.identity(), json.writeValueAsString(fingerprint.asMap()), result.success(),
                    result.toolUseSuccess(), result.usageComplete(), result.rateKnown(),
                    result.failureReason(), readiness.executionReady(),
                    json.writeValueAsString(readiness.blockers()), result.checkedAt());
            evidence.save(row);
            PilotInstallation installation = installations.lockById(1).orElseGet(PilotInstallation::initial);
            installation.setRuntimeReadinessRef(row.getId().toString());
            installations.save(installation);
        });
    }

    private Snapshot snapshot() {
        boolean runtimeConfigured;
        try {
            settings.coding().claudeCode().requireConfigured();
            runtimeConfigured = true;
        } catch (RuntimeException e) {
            runtimeConfigured = false;
        }
        String model = settings.coding().claudeCode().model();
        return new Snapshot(identity.current(), runtimeConfigured,
                "claude-code".equals(settings.coding().runtime()),
                pricing.rateFor(model).isPresent(),
                source.source() != null && source.sprintId() != null
                        && !source.statuses().isEmpty());
    }

    private PilotReadiness assemble(Snapshot snapshot, SmokeEvidence smokeEvidence, EvidenceState state) {
        List<String> blockers = new ArrayList<>();
        if (retention.enabled()) {
            blockers.add(RETENTION_BLOCKER);
        }
        if (!snapshot.runtimeConfigured()) {
            blockers.add(CONFIGURATION_BLOCKER);
        } else if (!snapshot.claudeCodeSelected()) {
            blockers.add(RUNTIME_SELECTION_BLOCKER);
        }
        if (!execution.enabled()) {
            blockers.add(EXECUTION_PROCESSING_BLOCKER);
        }
        if (!snapshot.rateKnown()) {
            blockers.add("No rate card for " + snapshot.fingerprint().model()
                    + "; add it to config/developer-pricing.yaml so smoke and task costs stay estimable.");
        }
        switch (state) {
            case ABSENT -> blockers.add(NOT_RUN_BLOCKER);
            case STALE -> blockers.add(STALE_BLOCKER);
            case FAILED -> blockers.add(smokeEvidence.failureReason() == null
                    ? SMOKE_FAILED_BLOCKER : smokeEvidence.failureReason());
            case CURRENT -> {
            }
        }
        boolean executionReady = blockers.isEmpty();

        boolean discoveryAvailable = snapshot.sourceConfigured() && installation().isSyncComplete();
        if (!snapshot.sourceConfigured()) {
            blockers.add(SOURCE_BLOCKER);
        } else if (!discoveryAvailable) {
            blockers.add(DISCOVERY_BLOCKER);
        }
        return new PilotReadiness(discoveryAvailable, executionReady, List.copyOf(blockers),
                snapshot.fingerprint().identity(), state == EvidenceState.CURRENT, smokeEvidence,
                smokeDisclosure(), smokeEvidence == null ? null : smokeEvidence.checkedAt());
    }

    private PilotInstallation installation() {
        return installations.findById(1).orElseGet(PilotInstallation::initial);
    }

    private SmokeEvidence smokeFrom(PilotReadinessEvidence row) {
        return new SmokeEvidence(row.getRuntimeFingerprint(), text(row.getFingerprint(), "image"),
                text(row.getFingerprint(), "runtime"), text(row.getFingerprint(), "model"),
                text(row.getFingerprint(), "endpointOrigin"),
                text(row.getFingerprint(), "instructionsFingerprint"), row.isToolUseSuccess(),
                row.isUsageComplete(), row.isRateKnown(), row.isPassed(), row.getFailureReason(),
                row.getCheckedAt());
    }

    private String text(String fingerprintJson, String field) {
        try {
            return json.readTree(fingerprintJson).path(field).asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String smokeDisclosure() {
        var profile = settings.coding().claudeCode();
        return "The start action verifies inference with one bounded smoke in the pinned coding image "
                + profile.image() + " with model " + profile.model() + ": a single tiny tool-using invocation,"
                + " at most " + bounds.smokeMaxTurns() + " turns and " + bounds.smokeTimeoutSeconds()
                + " seconds, in a throwaway container with no task repository, no Jira writes and no"
                + " publication. The smoke makes one paid model call and its usage is recorded separately"
                + " from task usage. Restarts and page views never repeat it: an unchanged runtime reuses"
                + " the recorded evidence.";
    }
}
