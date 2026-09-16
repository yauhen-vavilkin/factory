package org.folio.factory.connectors.jira;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.connectors.jira")
public record JiraProperties(String baseUrl, String email, String apiToken) {

    /** A base URL is enough for reads: public Jira instances allow anonymous REST reads. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean hasCredentials() {
        return email != null && !email.isBlank() && apiToken != null && !apiToken.isBlank();
    }
}
