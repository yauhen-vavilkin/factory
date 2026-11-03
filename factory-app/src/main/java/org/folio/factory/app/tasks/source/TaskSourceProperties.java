package org.folio.factory.app.tasks.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Whose Jira tasks My tasks shows: the assignee as Jira displays the name, or an
 * accountId. Jira resolves both forms in an anonymous search, so no Jira
 * credentials are needed to scan.
 */
@ConfigurationProperties("factory.tasks.source")
public record TaskSourceProperties(String assignee) {

    public TaskSourceProperties {
        assignee = assignee == null || assignee.isBlank() ? null : assignee.trim();
    }

    /** Every unfinished task assigned to the developer; statuses are filtered on the page. */
    public String effectiveQuery() {
        if (assignee == null) {
            throw new IllegalStateException(
                    "Set FACTORY_TASKS_ASSIGNEE to your name as shown in Jira, then run docker compose up -d");
        }
        String quoted = '"' + assignee.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        return "assignee = " + quoted + " AND statusCategory != Done ORDER BY updated DESC";
    }

    /** The same search in the Jira UI, so the developer can see exactly what Factory sees. */
    public String searchUrl(String jiraBaseUrl) {
        String base = jiraBaseUrl.endsWith("/") ? jiraBaseUrl.substring(0, jiraBaseUrl.length() - 1) : jiraBaseUrl;
        return base + "/issues/?jql=" + URLEncoder.encode(effectiveQuery(), StandardCharsets.UTF_8);
    }
}
