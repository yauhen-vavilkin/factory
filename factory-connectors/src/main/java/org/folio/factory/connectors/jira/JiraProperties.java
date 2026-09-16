package org.folio.factory.connectors.jira;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.connectors.jira")
public record JiraProperties(String baseUrl, String email, String apiToken) {

    /**
     * A base URL alone allows anonymous reads of public issues; writes still need
     * {@link #isConfigured() full credentials}.
     */
    public boolean isReadable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && email != null && !email.isBlank()
                && apiToken != null && !apiToken.isBlank();
    }
}
