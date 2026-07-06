package org.folio.factory.connectors.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.connectors.github")
public record GitHubProperties(String baseUrl, String token) {

    public GitHubProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "";
        }
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    public String effectiveBaseUrl() {
        return baseUrl.isBlank() ? "https://api.github.com" : baseUrl;
    }
}
