package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskRoutingReason;

import java.util.UUID;

/**
 * One task in Available. problem is null for a task that can run; problemLabel is its chip and
 * problemReason the stored sentence the fix dialog shows.
 */
public record AvailableRow(UUID taskId, String key, String jiraUrl, String title, String jiraStatus,
                           String projectKey, String repository, boolean addedByKey, boolean selectable,
                           long version, String inputFingerprint, String destinationFingerprint,
                           TaskRoutingReason.Problem problem, String problemLabel, String problemReason,
                           boolean shown) {
    AvailableRow shownAs(boolean visible) {
        return new AvailableRow(taskId, key, jiraUrl, title, jiraStatus, projectKey, repository, addedByKey,
                selectable, version, inputFingerprint, destinationFingerprint, problem, problemLabel,
                problemReason, visible);
    }
}
