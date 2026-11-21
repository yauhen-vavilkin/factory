package org.folio.factory.app.tasks.presentation;

/** Where My tasks come from; the Configuration tab shows the query and the search link. */
public record SourceCard(String assignee, String jiraHost, String query, String searchUrl, String lastScan,
                         String problem) {

    /** The title of Scan Jira: when the last scan ran. */
    public String scanTitle() {
        return lastScan != null ? "Last scan " + lastScan : "Not scanned yet";
    }

    /** What Ready to run says when it has no task to run. */
    public String emptyText() {
        return lastScan == null ? "Press Scan Jira to load the tasks assigned to you."
                : "No tasks to run. Press Scan Jira to check for new tasks assigned to you, or add one by its key.";
    }
}
