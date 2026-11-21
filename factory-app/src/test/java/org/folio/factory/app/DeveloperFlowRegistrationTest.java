package org.folio.factory.app;

import org.folio.factory.app.developer.DeveloperPreflightProbe;
import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.service.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Developer Flow is an ordinary plugin: registered next to Test Factory, visible
 * through the existing flow API/UI. Execution is covered by
 * {@link DeveloperIntakeDecisionTest}.
 */
@FactoryIntegrationTest
@TestPropertySource(properties = {
        "factory.connectors.jira.base-url=",
        "factory.dev-factory.runtime.coding.runtime=claude-code",
        // The default model without its secret must block admission.
        "FACTORY_CODEMIE_PROXY_TOKEN="})
// Own context: the default repository catalog with a real preflight; closing it stops its schedulers and frees its connections.
@DirtiesContext
class DeveloperFlowRegistrationTest {

    @LocalServerPort
    int port;

    @Autowired
    FlowRegistry flowRegistry;

    @Autowired
    StateManager state;

    @MockitoBean
    DeveloperPreflightProbe preflightProbe;

    private RestClient rest;

    @BeforeEach
    void setUpClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void registersAlongsideTestFactoryAndIsVisibleInExistingFlowViews() {
        assertThat(flowRegistry.require("dev-factory").name()).isEqualTo("Developer Flow");
        assertThat(flowRegistry.require("test-factory").name()).isEqualTo("Test Factory");

        JsonNode flows = rest.get().uri("/api/flows").retrieve().body(JsonNode.class);
        assertThat(flows.findValuesAsString("id")).contains("dev-factory", "test-factory");

        JsonNode detail = rest.get().uri("/api/flows/dev-factory").retrieve().body(JsonNode.class);
        assertThat(detail.path("name").asString()).isEqualTo("Developer Flow");
        assertThat(detail.path("steps").get(0).path("workerId").asString()).isEqualTo("dev-intake");

        assertThat(rest.get().uri("/flows").retrieve().body(String.class))
                .contains("Developer Flow").contains("Test Factory");
        assertThat(rest.get().uri("/flows/dev-factory").retrieve().body(String.class))
                .contains("Developer Flow").contains("Ready to run");
        assertThat(rest.get().uri("/flows/dev-factory?view=definition").retrieve().body(String.class))
                .contains("Developer Flow").contains("dev-intake");
    }

    @Test
    void realPreflightBlocksApiAdmissionAndTheUiHasNoJsonTrigger() throws Exception {
        long before = state.countCreatedSince(Instant.EPOCH);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var apiRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/triggers/manual"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"flowId":"dev-factory","payload":{"issueKey":"MODSIDECAR-196"}}
                        """))
                .build();

        var apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(apiResponse.statusCode()).isEqualTo(422);
        // The reason names the model and the variable to set.
        assertThat(apiResponse.body()).contains("Developer Flow preflight failed",
                "Model codemie-sonnet-5.5 is not configured: set FACTORY_CODEMIE_PROXY_TOKEN in .env");
        assertThat(state.countCreatedSince(Instant.EPOCH)).isEqualTo(before);

        String form = "payload=" + URLEncoder.encode("{\"issueKey\":\"MODSIDECAR-196\"}", StandardCharsets.UTF_8)
                + "&dedupKey=" + UUID.randomUUID();
        var uiRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/flows/dev-factory/trigger"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        var uiResponse = client.send(uiRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(uiResponse.statusCode()).isEqualTo(302);
        var location = URI.create(uiResponse.headers().firstValue("Location").orElseThrow());
        assertThat(location.getPath()).isEqualTo("/flows/dev-factory");
        // Developer Flow runs start from My tasks only; the JSON trigger form is refused.
        assertThat(URLDecoder.decode(location.getRawQuery(), StandardCharsets.UTF_8))
                .contains("Developer Flow runs start from My tasks");
        assertThat(state.countCreatedSince(Instant.EPOCH)).isEqualTo(before);
        verify(preflightProbe, times(1)).dockerAvailable();
    }
}
