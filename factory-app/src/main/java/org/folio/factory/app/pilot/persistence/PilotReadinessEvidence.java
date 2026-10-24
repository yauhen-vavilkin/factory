package org.folio.factory.app.pilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only row per explicit pilot startup action that ran the runtime
 * smoke (or attempted to): the tested runtime fingerprint identity, the smoke
 * observation and the readiness the run concluded. Fingerprints are hashes and
 * display identities only; no credential value is ever stored.
 */
@Entity
@Table(name = "pilot_readiness_evidence")
public class PilotReadinessEvidence {

    @Id
    private UUID id;

    @Column(name = "runtime_fingerprint", nullable = false, updatable = false, length = 64)
    private String runtimeFingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String fingerprint;

    @Column(nullable = false, updatable = false)
    private boolean passed;

    @Column(name = "tool_use_success", nullable = false, updatable = false)
    private boolean toolUseSuccess;

    @Column(name = "usage_complete", nullable = false, updatable = false)
    private boolean usageComplete;

    @Column(name = "rate_known", nullable = false, updatable = false)
    private boolean rateKnown;

    @Column(name = "failure_reason", updatable = false, length = 500)
    private String failureReason;

    @Column(name = "execution_ready", nullable = false, updatable = false)
    private boolean executionReady;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String blockers;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected PilotReadinessEvidence() {
    }

    public PilotReadinessEvidence(UUID id, String runtimeFingerprint, String fingerprint, boolean passed,
                                  boolean toolUseSuccess, boolean usageComplete, boolean rateKnown,
                                  String failureReason, boolean executionReady, String blockers,
                                  Instant checkedAt) {
        this.id = id;
        this.runtimeFingerprint = runtimeFingerprint;
        this.fingerprint = fingerprint;
        this.passed = passed;
        this.toolUseSuccess = toolUseSuccess;
        this.usageComplete = usageComplete;
        this.rateKnown = rateKnown;
        this.failureReason = failureReason;
        this.executionReady = executionReady;
        this.blockers = blockers;
        this.checkedAt = checkedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getRuntimeFingerprint() {
        return runtimeFingerprint;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isToolUseSuccess() {
        return toolUseSuccess;
    }

    public boolean isUsageComplete() {
        return usageComplete;
    }

    public boolean isRateKnown() {
        return rateKnown;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isExecutionReady() {
        return executionReady;
    }

    public String getBlockers() {
        return blockers;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
