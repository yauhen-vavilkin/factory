package org.folio.factory.connectors.jira;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Jira REST API v2 client (works with both Data Center and Cloud). Without
 * email/token it reads public issues anonymously and refuses every write.
 */
public class JiraRestConnector implements JiraConnector, ConnectorHealth {

    /** Where Jira Cloud redirects attachment downloads to; it is reached without Jira credentials. */
    private static final String MEDIA_HOST = "api.media.atlassian.com";
    private static final int MAX_REDIRECTS = 3;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    /** HttpRequest.timeout ends when the headers arrive, so a body that stalls is cut off by closing its stream. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jira-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final Pattern ATTACHMENT_ID = Pattern.compile("[0-9]{1,18}");

    private final RestClient restClient;
    private final boolean authenticated;
    private final String baseUrl;
    private final String authorization;
    private final Duration downloadTimeout;
    private final HttpClient downloads = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public JiraRestConnector(JiraProperties properties, RestClient.Builder builder) {
        this(properties, builder, DOWNLOAD_TIMEOUT);
    }

    JiraRestConnector(JiraProperties properties, RestClient.Builder builder, Duration downloadTimeout) {
        this.downloadTimeout = downloadTimeout;
        this.authenticated = properties.isConfigured();
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
        builder.baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (authenticated) {
            this.authorization = "Basic " + HttpHeaders.encodeBasicAuth(
                    properties.email(), properties.apiToken(), StandardCharsets.UTF_8);
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
        } else {
            this.authorization = null;
        }
        this.restClient = builder.build();
    }

    @Override
    public JiraIssue getIssue(String issueKey) {
        JsonNode body = restClient.get()
                .uri("/rest/api/2/issue/{key}", issueKey)
                .retrieve()
                .body(JsonNode.class);
        JsonNode fields = body.path("fields");
        List<String> labels = new ArrayList<>();
        fields.path("labels").forEach(label -> labels.add(label.asString("")));
        return new JiraIssue(
                body.path("key").asString(""),
                fields.path("summary").asString(""),
                fields.path("description").isTextual() ? fields.path("description").asString()
                        : fields.path("description").toString(),
                fields.path("status").path("name").asString(""),
                fields.path("issuetype").path("name").asString(""),
                labels,
                body);
    }

    @Override
    public void addComment(String issueKey, String body) {
        requireWriteAccess();
        restClient.post()
                .uri("/rest/api/2/issue/{key}/comment", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void transitionIssue(String issueKey, String transitionName) {
        requireWriteAccess();
        JsonNode transitions = restClient.get()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .retrieve()
                .body(JsonNode.class);
        String transitionId = null;
        for (JsonNode transition : transitions.path("transitions")) {
            if (transitionName.equalsIgnoreCase(transition.path("name").asString(""))) {
                transitionId = transition.path("id").asString("");
                break;
            }
        }
        if (transitionId == null) {
            throw new IllegalArgumentException(
                    "Issue " + issueKey + " has no transition named '" + transitionName + "'");
        }
        restClient.post()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("transition", Map.of("id", transitionId)))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Redirects are followed by hand: the Jira credentials go only to the Jira origin, and a redirect
     * may only lead back to it or to Jira Cloud's media host. Errors never carry a URL, because the
     * media redirect has an access token in its query.
     */
    @Override
    public byte[] downloadAttachment(String attachmentId, long maxBytes) {
        if (attachmentId == null || !ATTACHMENT_ID.matcher(attachmentId).matches()) {
            throw new IllegalArgumentException("Invalid Jira attachment id");
        }
        URI base = URI.create(baseUrl);
        URI uri = URI.create(baseUrl + "/rest/api/2/attachment/content/" + attachmentId);
        Instant deadline = Instant.now().plus(downloadTimeout);
        try {
            for (int redirects = 0; ; redirects++) {
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new IllegalStateException("Jira attachment download timed out");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(remaining).GET();
                if (authorization != null && sameOrigin(base, uri)) {
                    request.header(HttpHeaders.AUTHORIZATION, authorization);
                }
                HttpResponse<InputStream> response = downloads.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status >= 300 && status < 400) {
                        URI next = response.headers().firstValue("Location").map(uri::resolve).orElse(null);
                        if (next == null || redirects >= MAX_REDIRECTS || !redirectAllowed(base, next)) {
                            throw new IllegalStateException("Jira attachment download was redirected somewhere not allowed");
                        }
                        uri = next;
                        continue;
                    }
                    if (status != 200) {
                        throw new IllegalStateException("Jira attachment download failed with HTTP " + status);
                    }
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
                        throw new IllegalStateException("Jira attachment is larger than " + maxBytes + " bytes");
                    }
                    ScheduledFuture<?> guard = WATCHDOG.schedule(() -> closeQuietly(body),
                            Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
                    byte[] bytes;
                    try {
                        bytes = body.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
                    } finally {
                        guard.cancel(false);
                    }
                    if (bytes.length > maxBytes) {
                        throw new IllegalStateException("Jira attachment is larger than " + maxBytes + " bytes");
                    }
                    return bytes;
                }
            }
        } catch (IOException e) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Jira attachment download timed out", e);
            }
            throw new IllegalStateException("Jira attachment download failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Jira attachment download was interrupted", e);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing only aborts a stalled read.
        }
    }

    static boolean redirectAllowed(URI base, URI target) {
        if (sameOrigin(base, target)) {
            return true;
        }
        return "https".equalsIgnoreCase(target.getScheme()) && MEDIA_HOST.equalsIgnoreCase(target.getHost())
                && port(target) == 443;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void requireWriteAccess() {
        if (!authenticated) {
            throw new ConnectorNotConfiguredException("Jira connector is read-only: set "
                    + "FACTORY_CONNECTORS_JIRA_EMAIL and FACTORY_CONNECTORS_JIRA_API_TOKEN to enable writes");
        }
    }

    @Override
    public String connectorName() {
        return "jira";
    }

    @Override
    public boolean isConfigured() {
        return authenticated;
    }
}
