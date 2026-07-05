package org.folio.factory.connectors.testrail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.connectors.testrail")
public record TestRailProperties(String baseUrl, String username, String apiKey, Long projectId) {

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
