package org.folio.factory.app.developer;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local HTTP contract: verifies read-only GitHub probes, with no provider requests or git push. */
class DeveloperPreflightProbeTest {
    private static final String SOURCE = "a".repeat(40);
    private static final String DESTINATION = "b".repeat(40);
    private static final String REPOSITORY_URL = "/repos/fork/module";
    private static final String BRANCH_URL = REPOSITORY_URL + "/branches/campaign%2Fbase";
    private static final String COMPARE_URL = REPOSITORY_URL + "/compare/" + SOURCE + "..." + DESTINATION;
    private static final DeliveryTarget TARGET = new DeliveryTarget("fork/module", "campaign/base", true);
    private WireMockServer server;
    private DeveloperPreflightProbe probe;

    @BeforeEach
    void startLocalServer() {
        server = new WireMockServer(0);
        server.start();
        probe = new DeveloperPreflightProbe(new GitHubProperties(server.baseUrl(), "fixture-github-token"));
        stub(REPOSITORY_URL, "{\"permissions\":{\"push\":true}}");
    }

    @AfterEach
    void onlyReadRequestsWereSent() {
        try {
            assertThat(server.getAllServeEvents()).allSatisfy(event ->
                    assertThat(event.getRequest().getMethod().getName()).isEqualTo("GET"));
        } finally {
            server.stop();
        }
    }

    @Test
    void exactSourceHeadNeedsNoCompareAndSlashBranchIsOneEncodedSegment() {
        stub(BRANCH_URL, branch(SOURCE));
        assertThatCode(() -> probe.requireDeliveryAccess(TARGET, SOURCE)).doesNotThrowAnyException();
        server.verify(getRequestedFor(urlEqualTo(BRANCH_URL))
                .withHeader("Authorization", equalTo("Bearer fixture-github-token")));
        server.verify(0, getRequestedFor(urlPathMatching(".*/compare/.*")));
        assertThat(server.getAllServeEvents()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ahead", "identical"})
    void destinationContainingSourceIsAcceptedUsingSourceToDestinationComparison(String status) {
        stub(BRANCH_URL, branch(DESTINATION));
        stub(COMPARE_URL, "{\"status\":\"" + status + "\"}");
        assertThatCode(() -> probe.requireDeliveryAccess(TARGET, SOURCE)).doesNotThrowAnyException();
        server.verify(getRequestedFor(urlEqualTo(COMPARE_URL)));
        assertThat(server.getAllServeEvents()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"behind", "diverged", "unknown"})
    void destinationNotContainingSourceIsRejected(String status) {
        stub(BRANCH_URL, branch(DESTINATION));
        stub(COMPARE_URL, "{\"status\":\"" + status + "\"}");
        assertThatThrownBy(() -> probe.requireDeliveryAccess(TARGET, SOURCE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not contain the source base");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"permissions\":{\"push\":false}}", "{}"})
    void missingPushPermissionStopsBeforeBranchOrCompareProbe(String metadata) {
        stub(REPOSITORY_URL, metadata);
        assertThatThrownBy(() -> probe.requireDeliveryAccess(TARGET, SOURCE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no push permission");
        assertThat(server.getAllServeEvents()).hasSize(1);
        server.verify(0, getRequestedFor(urlPathMatching(".*/branches/.*")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-sha", ""})
    void invalidDestinationHeadCannotApproveDelivery(String sha) {
        stub(BRANCH_URL, branch(sha));
        assertThatThrownBy(() -> probe.requireDeliveryAccess(TARGET, SOURCE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Destination base is missing");
        server.verify(0, getRequestedFor(urlPathMatching(".*/compare/.*")));
    }

    @Test
    void gatewayProbeOnlyOpensTcpWithoutCallingAnApi() {
        assertThat(probe.gatewayReachable(URI.create(server.baseUrl()))).isTrue();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private void stub(String url, String body) {
        server.stubFor(get(urlEqualTo(url)).willReturn(okJson(body)));
    }

    private static String branch(String sha) {
        return "{\"commit\":{\"sha\":\"" + sha + "\"}}";
    }
}
