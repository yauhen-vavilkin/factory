package org.folio.factory.app.pilot.source;

import org.folio.factory.connectors.jira.JiraProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * The normalized Jira instance name pilot tasks are keyed by: the host of the
 * configured base URL in lower case, or a stable placeholder while unconfigured.
 * Discovery and automatic admission must agree on one derivation.
 */
@Component
public class PilotJiraInstance {

    private final JiraProperties jiraProperties;

    public PilotJiraInstance(JiraProperties jiraProperties) {
        this.jiraProperties = jiraProperties;
    }

    public String current() {
        String baseUrl = jiraProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "unconfigured";
        }
        URI uri = URI.create(baseUrl.strip());
        String host = uri.getHost();
        return host == null ? baseUrl.strip().toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
    }
}
