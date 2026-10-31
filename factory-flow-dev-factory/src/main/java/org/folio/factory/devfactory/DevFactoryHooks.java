package org.folio.factory.devfactory;

import org.folio.factory.devfactory.feedback.AttemptPricing;
import org.folio.factory.devfactory.repository.SelectedRepositoryPreflight;
import org.folio.factory.devfactory.runtime.CodingTranscripts;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;

/**
 * What the application may plug into the flow module. Each hook has a no-op default, so the module also runs
 * without the application's task persistence, pricing, transcripts or repository preflight.
 */
public record DevFactoryHooks(TaskAttemptPolicy taskPolicy, AttemptPricing pricing, CodingTranscripts transcripts,
                              SelectedRepositoryPreflight repositoryPreflight) {
}
