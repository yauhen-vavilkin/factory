package org.folio.factory.connectors.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubRestConnectorTest {

    private MockRestServiceServer server;
    private GitHubRestConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new GitHubRestConnector(new GitHubProperties(null, "ghp_token"), builder);
    }

    @Test
    void createBranchReadsBaseShaAndCreatesRef() {
        server.expect(requestTo("https://api.github.com/repos/folio-org/mod-agreements/git/ref/heads/main"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"object\": {\"sha\": \"abc123\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/folio-org/mod-agreements/git/refs"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.ref").value("refs/heads/test-factory/ERM-42"))
                .andExpect(jsonPath("$.sha").value("abc123"))
                .andRespond(withStatus(HttpStatus.CREATED));

        connector.createBranch("folio-org/mod-agreements", "main", "test-factory/ERM-42");
        server.verify();
    }

    @Test
    void commitFilesCreatesNewFileWhenAbsent() {
        server.expect(requestTo("https://api.github.com/repos/o/r/contents/tests/a.feature?ref=b"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("https://api.github.com/repos/o/r/contents/tests/a.feature"))
                .andExpect(method(PUT))
                .andExpect(jsonPath("$.branch").value("b"))
                .andExpect(jsonPath("$.content").exists())
                .andRespond(withSuccess());

        connector.commitFiles("o/r", "b", Map.of("tests/a.feature", "Feature: x"), "Add tests");
        server.verify();
    }

    @Test
    void commitFilesReusesShaWhenFileExists() {
        server.expect(requestTo("https://api.github.com/repos/o/r/contents/tests/a.feature?ref=b"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"sha\": \"existing123\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/o/r/contents/tests/a.feature"))
                .andExpect(method(PUT))
                .andExpect(jsonPath("$.sha").value("existing123"))
                .andExpect(jsonPath("$.branch").value("b"))
                .andRespond(withSuccess());

        connector.commitFiles("o/r", "b", Map.of("tests/a.feature", "Feature: x"), "Update tests");
        server.verify();
    }

    @Test
    void commitFilesRethrowsNon404ShaLookupFailure() {
        server.expect(requestTo("https://api.github.com/repos/o/r/contents/tests/a.feature?ref=b"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() ->
                connector.commitFiles("o/r", "b", Map.of("tests/a.feature", "Feature: x"), "Add tests"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        server.verify();
    }

    @Test
    void createPullRequestReturnsHtmlUrl() {
        server.expect(requestTo("https://api.github.com/repos/o/r/pulls"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.head").value("feature"))
                .andExpect(jsonPath("$.base").value("main"))
                .andRespond(withSuccess("{\"html_url\": \"https://github.com/o/r/pull/7\"}",
                        MediaType.APPLICATION_JSON));

        String url = connector.createPullRequest("o/r", "feature", "main", "Title", "Body");
        assertThat(url).isEqualTo("https://github.com/o/r/pull/7");
    }

    @Test
    void findsOnlyMatchingPullRequestInsideConfiguredFork() {
        server.expect(requestTo("https://api.github.com/repos/o/r/pulls?state=open&head=o:factory/task-123&base=main"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"html_url":"https://github.com/folio-org/r/pull/1",
                          "head":{"repo":{"full_name":"o/r"},"ref":"factory/task-123"},
                          "base":{"repo":{"full_name":"folio-org/r"},"ref":"main"}},
                         {"html_url":"https://github.com/o/r/pull/7",
                          "head":{"repo":{"full_name":"o/r"},"ref":"factory/task-123"},
                          "base":{"repo":{"full_name":"o/r"},"ref":"main"}}]
                        """, MediaType.APPLICATION_JSON));
        assertThat(connector.findOpenPullRequest("o/r", "factory/task-123", "main"))
                .contains("https://github.com/o/r/pull/7");
        server.verify();
    }

    @Test
    void findPullRequestsCoversOpenClosedAndMergedStatesWithHeadIdentity() {
        server.expect(requestTo("https://api.github.com/repos/o/r/pulls?state=all&head=o:factory/task-123&base=main"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [{"html_url":"https://github.com/o/r/pull/1","state":"open","merged":false,
                          "head":{"repo":{"full_name":"o/r"},"ref":"factory/task-123","sha":"sha-open"},
                          "base":{"repo":{"full_name":"o/r"},"ref":"main"}},
                         {"html_url":"https://github.com/o/r/pull/2","state":"closed","merged":false,
                          "head":{"repo":{"full_name":"o/r"},"ref":"factory/task-123","sha":"sha-closed"},
                          "base":{"repo":{"full_name":"o/r"},"ref":"main"}},
                         {"html_url":"https://github.com/o/r/pull/3","state":"closed","merged":true,
                          "head":{"repo":{"full_name":"o/r"},"ref":"factory/task-123","sha":"sha-merged"},
                          "base":{"repo":{"full_name":"o/r"},"ref":"main"}},
                         {"html_url":"https://github.com/other/r/pull/9","state":"open","merged":false,
                          "head":{"repo":{"full_name":"other/r"},"ref":"factory/task-123","sha":"sha-other"},
                          "base":{"repo":{"full_name":"other/r"},"ref":"main"}}]
                        """, MediaType.APPLICATION_JSON));

        var found = connector.findPullRequests("o/r", "factory/task-123", "main");

        assertThat(found).hasSize(3);
        assertThat(found).extracting(org.folio.factory.connectors.github.GitHubConnector.PullRequest::htmlUrl)
                .containsExactly(
                        "https://github.com/o/r/pull/1",
                        "https://github.com/o/r/pull/2",
                        "https://github.com/o/r/pull/3");
        assertThat(found).extracting(pr -> pr.state())
                .containsExactly("open", "closed", "closed");
        assertThat(found).extracting(org.folio.factory.connectors.github.GitHubConnector.PullRequest::merged)
                .containsExactly(false, false, true);
        assertThat(found).extracting(org.folio.factory.connectors.github.GitHubConnector.PullRequest::headSha)
                .containsExactly("sha-open", "sha-closed", "sha-merged");
        assertThat(found).extracting(org.folio.factory.connectors.github.GitHubConnector.PullRequest::headRef)
                .containsOnly("factory/task-123");
        assertThat(found).extracting(org.folio.factory.connectors.github.GitHubConnector.PullRequest::baseRef)
                .containsOnly("main");
        server.verify();
    }

    @Test
    void findPullRequestsReturnsEmptyListWhenNoPullRequestMatches() {
        server.expect(requestTo("https://api.github.com/repos/o/r/pulls?state=all&head=o:factory/task-123&base=main"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(connector.findPullRequests("o/r", "factory/task-123", "main")).isEmpty();
        server.verify();
    }
}
