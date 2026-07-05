package org.folio.factory.connectors.testrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestRailRestConnectorTest {

    private MockRestServiceServer server;
    private TestRailRestConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new TestRailRestConnector(
                new TestRailProperties("https://testrail.example.org", "bot", "key", 12L), builder);
    }

    @Test
    void addCaseReturnsId() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_case/55"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.title").value("Login works"))
                .andRespond(withSuccess("{\"id\": 991}", MediaType.APPLICATION_JSON));

        assertThat(connector.addCase(55, "Login works", "steps", "expected")).isEqualTo(991);
    }

    @Test
    void addRunUsesConfiguredProject() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_run/12"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.include_all").value(false))
                .andRespond(withSuccess("{\"id\": 300}", MediaType.APPLICATION_JSON));

        assertThat(connector.addRun("Flow A run", List.of(991L))).isEqualTo(300);
    }

    @Test
    void addResultsMapsPassFailToStatusIds() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_results_for_cases/300"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.results[0].case_id").value(991))
                .andExpect(jsonPath("$.results[0].status_id").value(1))
                .andRespond(withSuccess());

        connector.addResults(300, Map.of(991L, true), "automated");
        server.verify();
    }
}
