package org.folio.factory.app.tasks.presentation;

/** Where My tasks come from; the Configuration tab shows the query and the search link. */
public record SourceCard(String assignee, String jiraHost, String query, String searchUrl, String lastScan,
                         String lastScanTitle, String problem) {
}
