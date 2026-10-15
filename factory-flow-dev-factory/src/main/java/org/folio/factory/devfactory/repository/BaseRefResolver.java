package org.folio.factory.devfactory.repository;

import java.util.Optional;

/**
 * Resolves a configured branch to a full immutable commit SHA in the trusted
 * control plane.
 */
public interface BaseRefResolver {

    /**
     * @return the commit SHA, or empty when the branch does not exist
     * @throws org.folio.factory.core.agent.AgentExecutionException on transient failures
     */
    Optional<String> resolve(String sourceRepo, String branch);
}
