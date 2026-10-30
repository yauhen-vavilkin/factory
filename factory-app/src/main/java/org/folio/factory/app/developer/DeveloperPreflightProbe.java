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
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded read-only probes. Never sends an inference request or publishes Git changes. */
@Component
public class DeveloperPreflightProbe {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final int COMMAND_TIMEOUT_SECONDS = 5;
    private static final int SOURCE_TIMEOUT_SECONDS = 10;
    private static final int COMMAND_OUTPUT_LIMIT = 4096;
    private static final int GATEWAY_TIMEOUT_MILLIS = 3000;

    private final RestClient github;

    public DeveloperPreflightProbe(GitHubProperties properties) {
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(HTTP_TIMEOUT);
        http.setReadTimeout(HTTP_TIMEOUT);
        String token = properties.token() == null ? "" : properties.token();
        github = RestClient.builder()
                .baseUrl(properties.effectiveBaseUrl())
                .requestFactory(http)
                .defaultHeaders(headers -> headers.setBearerAuth(token))
                .build();
    }

    public boolean dockerAvailable() {
        return command(List.of("docker", "info", "--format", "{{.ServerVersion}}"));
    }

    public boolean imageAvailable(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        return command(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image));
    }

    public boolean gatewayReachable(URI endpoint) {
        int port = endpoint.getPort();
        if (port < 0) {
            port = "https".equals(endpoint.getScheme()) ? 443 : 80;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHost(), port), GATEWAY_TIMEOUT_MILLIS);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public String sourceBase(String gitBaseUrl, String sourceRepo, String branch) {
        String branchRef = "refs/heads/" + branch;
        List<String> command = List.of("git", "-c", "credential.helper=", "ls-remote", "--exit-code",
                gitBaseUrl + "/" + sourceRepo + ".git", branchRef);
        Processes.Result result = Processes.run(null, command, SOURCE_TIMEOUT_SECONDS, Map.of(), COMMAND_OUTPUT_LIMIT);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Public source base is not readable");
        }
        for (String line : result.output().lines().toList()) {
            String[] parts = line.split("\t");
            if (parts.length == 2 && branchRef.equals(parts[1]) && SHA.matcher(parts[0]).matches()) {
                return parts[0];
            }
        }
        throw new IllegalStateException("Public source base is missing");
    }

    public void requireDeliveryAccess(DeliveryTarget target, String sourceSha) {
        requirePushAccess(target);
        String[] repository = target.repository().split("/");
        String owner = repository[0];
        String name = repository[1];

        String destinationSha = destinationBase(owner, name, target.baseBranch());
        if (!sourceSha.equals(destinationSha)) {
            requireSourceAncestor(owner, name, sourceSha, destinationSha);
        }
    }

    /** The configured token can push to the delivery repository. */
    public void requirePushAccess(DeliveryTarget target) {
        target.requireValid();
        String[] repository = target.repository().split("/");
        requirePushPermission(repository[0], repository[1]);
    }

    private void requirePushPermission(String owner, String repository) {
        JsonNode metadata = github.get()
                .uri(uri -> uri.pathSegment("repos", owner, repository).build())
                .retrieve()
                .body(JsonNode.class);
        if (metadata == null || !metadata.path("permissions").path("push").asBoolean(false)) {
            throw new IllegalStateException("GitHub token has no push permission for the destination");
        }
    }

    private String destinationBase(String owner, String repository, String baseBranch) {
        JsonNode branch = github.get()
                .uri(uri -> uri.pathSegment("repos", owner, repository, "branches", baseBranch).build())
                .retrieve()
                .body(JsonNode.class);
        String destinationSha = branch == null ? "" : branch.path("commit").path("sha").asString("");
        if (!SHA.matcher(destinationSha).matches()) {
            throw new IllegalStateException("Destination base is missing");
        }
        return destinationSha;
    }

    private void requireSourceAncestor(String owner, String repository, String sourceSha, String destinationSha) {
        String comparisonRange = sourceSha + "..." + destinationSha;
        JsonNode comparison = github.get()
                .uri(uri -> uri.pathSegment("repos", owner, repository, "compare", comparisonRange).build())
                .retrieve()
                .body(JsonNode.class);
        String status = comparison == null ? "" : comparison.path("status").asString("");
        if (!"ahead".equals(status) && !"identical".equals(status)) {
            throw new IllegalStateException("Destination does not contain the source base; synchronize the fork");
        }
    }

    private boolean command(List<String> argv) {
        try {
            Processes.Result result = Processes.run(null, argv, COMMAND_TIMEOUT_SECONDS, Map.of(), COMMAND_OUTPUT_LIMIT);
            return result.exitCode() == 0;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
