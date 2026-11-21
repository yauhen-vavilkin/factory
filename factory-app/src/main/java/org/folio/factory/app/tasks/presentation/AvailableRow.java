package org.folio.factory.app.tasks.presentation;

import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.UiFormat;

import java.util.UUID;

/** One task in Ready to run. problem is null for a task that can run; problemReason is the stored sentence. */
public record AvailableRow(UUID taskId, String key, String jiraUrl, String title, String jiraStatus,
                           String projectKey, String repository, boolean addedByKey, boolean selectable,
                           long version, String inputFingerprint, String destinationFingerprint,
                           TaskRoutingReason.Problem problem, String problemReason) {

    /** The whole meta line, for its title when a narrow row cuts it. */
    public String metaTitle() {
        return UiFormat.joinNonNull(key, "Status in Jira: " + jiraStatus, repository);
    }
}
