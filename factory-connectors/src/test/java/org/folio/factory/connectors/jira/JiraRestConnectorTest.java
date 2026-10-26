package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.jira.JiraSourceModels.DiscoveredIssue;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraRestConnectorTest {

    private static final String SEARCH_PAGE_ONE = """
            {"isLast": false, "nextPageToken": "CURSOR-2", "issues": [
              {"id": "10001", "key": "MGRAPPS-11", "fields": {
                 "summary": "Entitlement dependency check",
                 "description": "As a user...",
                 "status": {"name": "To Do"},
                 "issuetype": {"name": "Story"},
                 "labels": ["ai-factory"],
                 "created": "2026-10-01T09:30:00.000+0000",
                 "project": {"key": "MGRAPPS"},
                 "components": [{"name": "backend"}, {"name": "api"}]}},
              {"id": "10002", "key": "MGRENTITLE-7", "fields": {
                 "summary": "Blank optional fields",
                 "description": "Describe the work",
                 "status": {"name": "Ready for Dev"},
                 "issuetype": {"name": "Task"},
                 "labels": [],
                 "created": "2026-10-02T14:00:00.000+0000",
                 "project": {"key": "MGRENTITLE"},
                 "components": []}}]}
            """;

    private static final String SEARCH_PAGE_TWO = """
            {"isLast": true, "issues": [
              {"id": "10003", "key": "MGRAPPS-12", "fields": {
                 "summary": "Third match",
                 "description": "Last page",
                 "status": {"name": "To Do"},
                 "issuetype": {"name": "Story"},
                 "labels": [],
                 "created": "2026-10-03T08:15:00.000+0000",
                 "project": {"key": "MGRAPPS"},
                 "components": []}}]}
            """;

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

    @Test
    void baseUrlOnlySearchesAnonymously() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer anonymousServer = MockRestServiceServer.bindTo(builder).build();
        JiraRestConnector anonymous = new JiraRestConnector(
                new JiraProperties("https://jira.example.org", null, null), builder);
        anonymousServer.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.jql").value("key = MGRAPPS-11"))
                .andRespond(withSuccess(SEARCH_PAGE_TWO, MediaType.APPLICATION_JSON));

        assertThat(anonymous.searchIssues("key = MGRAPPS-11", null).issues()).hasSize(1);
        anonymousServer.verify();
    }

    @Test
    void searchIssuesReadsAllPagesUntilExhausted() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"jql": "sprint = 4711 ORDER BY created ASC", "maxResults": 50,
                         "fields": ["summary", "description", "status", "issuetype",
                                    "labels", "project", "components", "created",
                                    "issuelinks", "comment"]}
                        """))
                .andExpect(jsonPath("$.nextPageToken").doesNotExist())
                .andRespond(withSuccess(SEARCH_PAGE_ONE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.jql").value("sprint = 4711 ORDER BY created ASC"))
                .andExpect(jsonPath("$.nextPageToken").value("CURSOR-2"))
                .andRespond(withSuccess(SEARCH_PAGE_TWO, MediaType.APPLICATION_JSON));

        IssuePage first = connector.searchIssues("sprint = 4711 ORDER BY created ASC", null);
        IssuePage last = connector.searchIssues("sprint = 4711 ORDER BY created ASC", first.nextCursor());

        assertThat(first.issues()).hasSize(2);
        assertThat(first.nextCursor()).isEqualTo("CURSOR-2");
        assertThat(last.issues()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
        List<DiscoveredIssue> allPages = new ArrayList<>(first.issues());
        allPages.addAll(last.issues());
        assertThat(allPages.stream().map(DiscoveredIssue::projectKey).distinct().toList())
                .containsExactlyInAnyOrder("MGRAPPS", "MGRENTITLE");
        assertThat(allPages).extracting(DiscoveredIssue::id)
                .containsExactly("10001", "10002", "10003");
        assertThat(allPages.get(0).key()).isEqualTo("MGRAPPS-11");
        assertThat(allPages.get(0).components()).containsExactly("backend", "api");
        assertThat(allPages.get(1).components()).isEmpty();
        assertThat(allPages.get(0).createdAt()).isEqualTo(Instant.parse("2026-10-01T09:30:00Z"));
        assertThat(allPages.get(0).issue().summary()).isEqualTo("Entitlement dependency check");
        assertThat(allPages.get(0).issue().status()).isEqualTo("To Do");
        assertThat(allPages.get(0).issue().labels()).containsExactly("ai-factory");
        server.verify();
    }

    @Test
    void searchIssuesTreatsEmptyPageWithoutTokenAsComplete() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"isLast\": true, \"issues\": []}", MediaType.APPLICATION_JSON));

        IssuePage page = connector.searchIssues("sprint = 4711", null);

        assertThat(page.issues()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        server.verify();
    }

    @Test
    void searchIssuesFailsInsteadOfReturningPartialResultWhenLaterPageFails() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andRespond(withSuccess(SEARCH_PAGE_ONE, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andExpect(method(POST))
                .andRespond(withServerError());

        IssuePage first = connector.searchIssues("sprint = 4711", null);

        assertThat(first.issues()).hasSize(2);
        assertThatThrownBy(() -> connector.searchIssues("sprint = 4711", first.nextCursor()))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }

    @Test
    void searchIssuesRejectsResponsesWithoutPaginationContract() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andRespond(withSuccess("{\"isLast\": false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/search/jql"))
                .andRespond(withSuccess("{\"issues\": [], \"nextPageToken\": \"CURSOR-2\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.searchIssues("sprint = 4711", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'issues' is missing");
        assertThatThrownBy(() -> connector.searchIssues("sprint = 4711", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty issues page")
                .hasMessageContaining("nextPageToken");
        server.verify();
    }
}
