package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskRoutingReason;

import java.util.UUID;

/** One task in Ready to run. problem is null for a task that can run; problemReason is the stored sentence. */
public record AvailableRow(UUID taskId, String key, String jiraUrl, String title, String jiraStatus,
                           String projectKey, String repository, boolean addedByKey, boolean selectable,
                           long version, String inputFingerprint, String destinationFingerprint,
                           TaskRoutingReason.Problem problem, String problemReason) {
}
