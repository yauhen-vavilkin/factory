package org.folio.factory.sandbox.harness;

/**
 * The validated task outcome: whether the run produced a useful result for
 * the filed task contract. This is deliberately a third layer, distinct from
 * engine termination ({@code ExecutionStatus}) and from the model run
 * outcome ({@code HarnessReport.Outcome}): a model that stops early with no
 * change, or a run whose steps/time budget ran out, has not delivered a
 * validated success even though the engine itself terminated normally.
 */
public enum TaskOutcome {

  /** The run's result satisfies the task contract's useful-work bar. */
  SUCCEEDED,

  /** The run cannot claim a useful result; see the reason for the boundary. */
  FAILED;

  /** Why the validated outcome was reached. */
  public enum Reason {

    /** Model completed, the diff is non-empty and a final report was given. */
    CHANGES_DELIVERED,

    /** No change, the contract permits a no-op and verification evidence exists. */
    NO_OP_VERIFIED,

    /** The model run itself failed (steps/format/timeout/model error). */
    MODEL_RUN_FAILED,

    /** No change and the contract does not permit a no-op result. */
    NO_OP_NOT_PERMITTED,

    /** No change, no-op permitted, but no successful verification tool ran. */
    NO_VERIFICATION_EVIDENCE,

    /** Model completed without any final report text. */
    MISSING_FINAL_REPORT
  }
}
