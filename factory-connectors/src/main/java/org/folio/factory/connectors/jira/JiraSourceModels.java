package org.folio.factory.connectors.jira;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable discovery models for the pilot team source. Identity is anchored to
 * the immutable Jira issue id; the issue key is display data that can change.
 */
public final class JiraSourceModels {

    private JiraSourceModels() {
    }

    public enum Kind {
        BOARD, FILTER
    }

    public record SourceReference(Kind kind, String id) {

        private static final Pattern PREFIXED = Pattern.compile("(?i)(board|filter):(\\d+)");
        private static final Pattern BOARD_PATH = Pattern.compile(".*/boards/(\\d+)/?");
        private static final Pattern NUMERIC_QUERY = Pattern.compile("(\\d+)");

        public SourceReference {
            if (kind == null || id == null || !id.matches("\\d+")) {
                throw new IllegalArgumentException("Source reference requires a kind and a numeric id");
            }
        }

        public static SourceReference parse(String raw, String baseUrl) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                        "Team source is required: use a board or saved filter id or URL");
            }
            String value = raw.trim();
            if (value.matches("\\d+")) {
                return new SourceReference(Kind.BOARD, value);
            }
            Matcher prefixed = PREFIXED.matcher(value);
            if (prefixed.matches()) {
                return new SourceReference(Kind.valueOf(prefixed.group(1).toUpperCase()), prefixed.group(2));
            }
            if (value.contains("://")) {
                return parseUrl(value, baseUrl);
            }
            throw new IllegalArgumentException("Unsupported team source '" + raw
                    + "': use a numeric id, board:/filter: form, or a board or saved filter URL");
        }

        private static SourceReference parseUrl(String raw, String baseUrl) {
            URI url;
            try {
                url = URI.create(raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Team source '" + raw + "' is not a valid URL", e);
            }
            URI base = URI.create(baseUrl == null ? "" : baseUrl);
            if (base.getHost() == null || !sameInstance(url, base)) {
                throw new IllegalArgumentException("Team source URL '" + raw
                        + "' belongs to a different Jira instance than the configured base URL '" + baseUrl + "'");
            }
            Matcher boardPath = BOARD_PATH.matcher(url.getPath());
            if (boardPath.matches()) {
                return new SourceReference(Kind.BOARD, boardPath.group(1));
            }
            String rapidView = numericQueryParam(url, "rapidView");
            if (rapidView != null) {
                return new SourceReference(Kind.BOARD, rapidView);
            }
            String filter = numericQueryParam(url, "filter");
            if (filter != null) {
                return new SourceReference(Kind.FILTER, filter);
            }
            String requestId = numericQueryParam(url, "requestId");
            if (requestId != null) {
                return new SourceReference(Kind.FILTER, requestId);
            }
            throw new IllegalArgumentException("Unsupported Jira URL '" + raw
                    + "': expected a board URL (.../boards/<id> or rapidView=<id>) "
                    + "or a saved filter URL (filter=<id> or requestId=<id>)");
        }

        private static boolean sameInstance(URI url, URI base) {
            return url.getScheme() != null && url.getScheme().equalsIgnoreCase(base.getScheme())
                    && url.getHost() != null && url.getHost().equalsIgnoreCase(base.getHost())
                    && effectivePort(url) == effectivePort(base);
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() > 0) {
                return uri.getPort();
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

        private static String numericQueryParam(URI url, String name) {
            String query = url.getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0 && name.equalsIgnoreCase(pair.substring(0, equals))
                        && NUMERIC_QUERY.matcher(pair.substring(equals + 1)).matches()) {
                    return pair.substring(equals + 1);
                }
            }
            return null;
        }
    }

    public record ResolvedSource(String id, String name, URI link, String filterId, String query) {

        public ResolvedSource {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Resolved source requires an id");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Resolved source requires a name");
            }
            if (link == null) {
                throw new IllegalArgumentException("Resolved source requires a link");
            }
            if (filterId == null || filterId.isBlank()) {
                throw new IllegalArgumentException("Resolved source requires a filter id");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("Resolved source requires a query");
            }
        }
    }

    public record Sprint(String id, String name) {

        public Sprint {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Sprint requires an id");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Sprint requires a name");
            }
        }
    }

    public record DiscoveredIssue(
            String id,
            String key,
            String projectKey,
            List<String> components,
            Instant createdAt,
            JiraIssue issue) {

        public DiscoveredIssue {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Discovered issue requires an immutable id");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Discovered issue " + id + " requires a key");
            }
            if (projectKey == null || projectKey.isBlank()) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires a project key");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires a created time");
            }
            if (issue == null) {
                throw new IllegalArgumentException("Discovered issue " + key + " requires issue content");
            }
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    public record IssuePage(List<DiscoveredIssue> issues, String nextCursor) {

        public IssuePage {
            if (issues == null) {
                throw new IllegalArgumentException("Issue page requires issues");
            }
            issues = List.copyOf(issues);
        }
    }
}
