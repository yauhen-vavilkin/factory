package org.folio.factory.app.pilot.source;

import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.devfactory.intake.IssueSnapshot;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The configured Jira instance: the normalized name pilot tasks are keyed by
 * (the host of the base URL in lower case, or a stable placeholder while
 * unconfigured) and the parsing of a task key the developer types or pastes.
 */
@Component
public class PilotJiraInstance {

    private static final Pattern BROWSE_PATH = Pattern.compile("/browse/([^/?#]+)/?$");

    private final JiraProperties jiraProperties;

    public PilotJiraInstance(JiraProperties jiraProperties) {
        this.jiraProperties = jiraProperties;
    }

    public String current() {
        URI base = base();
        if (base == null) {
            return "unconfigured";
        }
        String host = base.getHost();
        return host == null ? baseUrl().toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
    }

    /** The configured base URL without a trailing slash, or null while Jira is unconfigured. */
    public String baseUrl() {
        String baseUrl = jiraProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * A task key from {@code ABC-123} or a {@code /browse/ABC-123} link on the
     * configured Jira; anything else is refused before Jira is called.
     */
    public String issueKey(String input) {
        String text = input == null ? "" : input.strip();
        String candidate = text;
        if (text.contains("/")) {
            candidate = browsedKey(text);
        }
        candidate = candidate.toUpperCase(Locale.ROOT);
        if (!IssueSnapshot.ISSUE_KEY.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Enter a Jira key like MGRAPPS-101 or a link to it on "
                    + (baseUrl() == null ? "the configured Jira" : baseUrl()));
        }
        return candidate;
    }

    private String browsedKey(String text) {
        URI link;
        try {
            link = URI.create(text);
        } catch (IllegalArgumentException e) {
            return "";
        }
        URI base = base();
        if (base == null || link.getHost() == null || !sameInstance(link, base) || link.getPath() == null) {
            return "";
        }
        Matcher matcher = BROWSE_PATH.matcher(link.getPath());
        return matcher.find() ? matcher.group(1) : "";
    }

    private URI base() {
        String baseUrl = baseUrl();
        return baseUrl == null ? null : URI.create(baseUrl);
    }

    private static boolean sameInstance(URI link, URI base) {
        return link.getScheme() != null && link.getScheme().equalsIgnoreCase(base.getScheme())
                && link.getHost().equalsIgnoreCase(base.getHost())
                && effectivePort(link) == effectivePort(base);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
