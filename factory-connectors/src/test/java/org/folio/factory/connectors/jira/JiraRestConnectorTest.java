package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraRestConnectorTest {

    private MockRestServiceServer server;
    private JiraRestConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new JiraRestConnector(
                new JiraProperties("https://jira.example.org", "bot@example.org", "secret"), builder);
    }

    @Test
    void getIssueMapsFields() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {
                            "summary": "Add agreement endpoint",
                            "description": "As a user...",
                            "status": {"name": "Ready for QA"},
                            "issuetype": {"name": "Story"},
                            "labels": ["ai-factory"]}}
                        """, MediaType.APPLICATION_JSON));

        JiraIssue issue = connector.getIssue("ERM-42");

        assertThat(issue.key()).isEqualTo("ERM-42");
        assertThat(issue.summary()).isEqualTo("Add agreement endpoint");
        assertThat(issue.status()).isEqualTo("Ready for QA");
        assertThat(issue.issueType()).isEqualTo("Story");
        assertThat(issue.labels()).containsExactly("ai-factory");
        server.verify();
    }

    @Test
    void getIssueSerializesNonTextualDescription() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {
                            "summary": "Add agreement endpoint",
                            "description": {"type": "doc", "content": [{"type": "paragraph"}]},
                            "status": {"name": "Ready for QA"},
                            "issuetype": {"name": "Story"},
                            "labels": []}}
                        """, MediaType.APPLICATION_JSON));

        JiraIssue issue = connector.getIssue("ERM-42");

        assertThat(issue.description())
                .contains("\"type\":\"doc\"")
                .contains("\"content\":[{\"type\":\"paragraph\"}]");
        server.verify();
    }

    @Test
    void transitionResolvesIdByName() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"transitions": [
                          {"id": "11", "name": "In Progress"},
                          {"id": "31", "name": "QA Complete"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.transition.id").value("31"))
                .andRespond(withSuccess());

        connector.transitionIssue("ERM-42", "QA Complete");
        server.verify();
    }

    @Test
    void transitionFailsWhenNameUnknown() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andRespond(withSuccess("{\"transitions\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.transitionIssue("ERM-42", "Nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no transition named");
    }

    @Test
    void addCommentPostsBody() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/comment"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"body\": \"Test factory finished\"}"))
                .andRespond(withSuccess());

        connector.addComment("ERM-42", "Test factory finished");
        server.verify();
    }

    @Test
    void baseUrlOnlyReadsAnonymouslyAndRefusesWrites() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer anonymousServer = MockRestServiceServer.bindTo(builder).build();
        JiraRestConnector anonymous = new JiraRestConnector(
                new JiraProperties("https://jira.example.org", null, null), builder);
        anonymousServer.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"key\": \"ERM-42\", \"fields\": {\"summary\": \"Public\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(anonymous.getIssue("ERM-42").summary()).isEqualTo("Public");
        assertThat(anonymous.isConfigured()).isFalse();
        assertThatThrownBy(() -> anonymous.addComment("ERM-42", "x"))
                .isInstanceOf(ConnectorNotConfiguredException.class);
        assertThatThrownBy(() -> anonymous.transitionIssue("ERM-42", "Done"))
                .isInstanceOf(ConnectorNotConfiguredException.class);
        anonymousServer.verify();
    }
}
