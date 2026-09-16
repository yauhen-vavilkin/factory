package org.folio.factory.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.connectors.jira.JiraWriteGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Jira read access does not depend on the external-write switch; writes fail closed. */
class ConnectorsConfigurationTest {

    private final ConnectorsConfiguration configuration = new ConnectorsConfiguration();

    @Test
    void configuredJiraReadsWorkWithExternalWritesDisabledAndWritesFailClosed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // The configuration clones the builder; the clone keeps the mock request factory.
        JiraConnector jira = configuration.jiraConnector(
                new JiraProperties("https://jira.example.org", "bot@example.org", "secret"), builder, false);
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42?expand=changelog"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"key\": \"ERM-42\", \"fields\": {\"summary\": \"Read\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(jira).isInstanceOf(JiraWriteGuard.class);
        assertThat(((ConnectorHealth) jira).isConfigured()).isTrue();
        assertThat(jira.getIssue("ERM-42").summary()).isEqualTo("Read");
        assertThatThrownBy(() -> jira.addComment("ERM-42", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("writes are disabled");
        assertThatThrownBy(() -> jira.transitionIssue("ERM-42", "Done"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("writes are disabled");
        server.verify();
    }

    @Test
    void missingBaseUrlStillBindsTheSelfDescribingFallback() {
        JiraConnector jira = configuration.jiraConnector(new JiraProperties("", "", ""), RestClient.builder(), false);

        assertThat(jira).isInstanceOf(UnconfiguredConnectors.Jira.class);
        assertThatThrownBy(() -> jira.getIssue("ERM-42")).isInstanceOf(ConnectorNotConfiguredException.class);
    }
}
