package org.folio.factory.app.developer;

import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.Processes;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded read-only probes. Never sends an inference request or publishes Git changes. */
@Component
public class DeveloperPreflightProbe {
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private final RestClient github;

    public DeveloperPreflightProbe(GitHubProperties properties) {
        var http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(5));
        http.setReadTimeout(Duration.ofSeconds(5));
        github = RestClient.builder().baseUrl(properties.effectiveBaseUrl()).requestFactory(http)
                .defaultHeaders(headers -> headers.setBearerAuth(properties.token() == null ? "" : properties.token()))
                .build();
    }

    public boolean dockerAvailable() {
        return command(List.of("docker", "info", "--format", "{{.ServerVersion}}"));
    }

    public boolean imageAvailable(String image) {
        if (image == null || image.isBlank()) return false;
        return command(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
    }

    public boolean gatewayReachable(URI endpoint) {
        int port = endpoint.getPort() >= 0 ? endpoint.getPort() : "https".equals(endpoint.getScheme()) ? 443 : 80;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHost(), port), 3000);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public String sourceBase(String gitBaseUrl, String sourceRepo, String branch) {
        var result = Processes.run(null, List.of("git", "-c", "credential.helper=", "ls-remote", "--exit-code",
                gitBaseUrl + "/" + sourceRepo + ".git", "refs/heads/" + branch), 10, java.util.Map.of(), 4096);
        if (result.exitCode() != 0) throw new IllegalStateException("Public source base is not readable");
        for (String line : result.output().lines().toList()) {
            String[] parts = line.split("\t");
            if (parts.length == 2 && parts[1].equals("refs/heads/" + branch) && SHA.matcher(parts[0]).matches())
                return parts[0];
        }
        throw new IllegalStateException("Public source base is missing");
    }

    public void requireDeliveryAccess(DeliveryTarget target, String sourceSha) {
        String[] repository = target.repository().split("/");
        var metadata = github.get().uri(uri -> uri.pathSegment("repos", repository[0], repository[1]).build())
                .retrieve().body(JsonNode.class);
        if (metadata == null || !metadata.path("permissions").path("push").asBoolean(false))
            throw new IllegalStateException("GitHub token has no push permission for the destination");
        var branch = github.get().uri(uri -> uri.pathSegment("repos", repository[0], repository[1], "branches",
                        target.baseBranch()).build()).retrieve().body(JsonNode.class);
        String destinationSha = branch == null ? "" : branch.path("commit").path("sha").asString("");
        if (!SHA.matcher(destinationSha).matches()) throw new IllegalStateException("Destination base is missing");
        if (sourceSha.equals(destinationSha)) return;
        var comparison = github.get().uri(uri -> uri.pathSegment("repos", repository[0], repository[1], "compare",
                        sourceSha + "..." + destinationSha).build()).retrieve().body(JsonNode.class);
        String status = comparison == null ? "" : comparison.path("status").asString("");
        if (!List.of("ahead", "identical").contains(status))
            throw new IllegalStateException("Destination does not contain the source base; synchronize the fork");
    }

    private boolean command(List<String> argv) {
        try {
            return Processes.run(null, argv, 5, java.util.Map.of(), 4096).exitCode() == 0;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
