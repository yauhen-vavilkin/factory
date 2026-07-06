package org.folio.factory.core.agent;

/**
 * A stateless, single-responsibility processing unit. Workers are defined once and
 * reused by any flow that references their id in an AGENT step. All state flows
 * through immutable artifacts — workers must not retain per-execution state.
 */
public interface AgentWorker {

    /**
     * Stable identifier referenced by flow descriptors as {@code worker_id}.
     */
    String id();

    AgentResult execute(AgentContext context) throws AgentExecutionException;
}
