package org.folio.factory.connectors;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.github.GitHubRestConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.connectors.jira.JiraRestConnector;
import org.folio.factory.connectors.jira.JiraWriteGuard;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.connectors.testrail.TestRailProperties;
import org.folio.factory.connectors.testrail.TestRailRestConnector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Binds the real REST connector when its base credentials are present, and the
 * self-describing "not configured" fallback otherwise. Presence is decided at
 * runtime from the bound properties (env vars may be blank rather than absent).
 */
@Configuration
@EnableConfigurationProperties({JiraProperties.class, GitHubProperties.class, TestRailProperties.class})
public class ConnectorsConfiguration {

    /**
     * Jira read access and write access are separate concerns: the REST
     * connector is bound whenever credentials are present, so read-only task
     * intake works with external writes disabled; the write guard then refuses
     * every mutating call instead of unbinding the connector entirely.
     */
    @Bean
    public JiraConnector jiraConnector(JiraProperties properties, RestClient.Builder builder,
                                       @Value("${factory.connectors.external-writes-enabled:true}")
                                       boolean externalWritesEnabled) {
        if (!properties.isConfigured()) {
            return new UnconfiguredConnectors.Jira();
        }
        JiraRestConnector rest = new JiraRestConnector(properties, builder.clone());
        return externalWritesEnabled ? rest : new JiraWriteGuard(rest);
    }

    @Bean
    public GitHubConnector gitHubConnector(GitHubProperties properties, RestClient.Builder builder,
                                           @Value("${factory.connectors.external-writes-enabled:true}")
                                           boolean externalWritesEnabled) {
        return externalWritesEnabled && properties.isConfigured()
                ? new GitHubRestConnector(properties, builder.clone())
                : new UnconfiguredConnectors.GitHub();
    }

    @Bean
    public TestRailConnector testRailConnector(TestRailProperties properties, RestClient.Builder builder,
                                               @Value("${factory.connectors.external-writes-enabled:true}")
                                               boolean externalWritesEnabled) {
        return externalWritesEnabled && properties.isConfigured()
                ? new TestRailRestConnector(properties, builder.clone())
                : new UnconfiguredConnectors.TestRail();
    }

    // Expose each connector under the ConnectorHealth type explicitly: @Bean
    // factory methods advertise their declared return type, so a List<ConnectorHealth>
    // injection point would not otherwise see these beans.

    @Bean
    public ConnectorHealth jiraConnectorHealth(JiraConnector connector) {
        return (ConnectorHealth) connector;
    }

    @Bean
    public ConnectorHealth gitHubConnectorHealth(GitHubConnector connector) {
        return (ConnectorHealth) connector;
    }

    @Bean
    public ConnectorHealth testRailConnectorHealth(TestRailConnector connector) {
        return (ConnectorHealth) connector;
    }
}
