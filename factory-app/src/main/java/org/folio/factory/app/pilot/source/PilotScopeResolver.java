package org.folio.factory.app.pilot.source;

import org.folio.factory.app.pilot.Digests;
import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.ResolvedSource;
import org.folio.factory.connectors.jira.JiraSourceModels.Sprint;
import org.folio.factory.connectors.jira.JiraSourceModels.SourceReference;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Composes the pilot effective Jira query from the inherited team source and
 * the configured sprint, statuses and optional restrictions. Resolution data
 * comes from the connector; composition itself performs no HTTP calls.
 */
public class PilotScopeResolver {

    private static final String ORDER_BY = "order by";

    private final JiraSourceConnector connector;

    public PilotScopeResolver(JiraSourceConnector connector) {
        this.connector = connector;
    }

    public PilotScope resolve(PilotSourceProperties properties) {
        requireInput(properties);
        SourceReference reference = connector.parseSourceReference(properties.source());
        return resolve(properties, connector.resolveSource(reference),
                connector.getSprint(properties.sprintId()));
    }

    public PilotScope resolve(PilotSourceProperties properties, ResolvedSource source, Sprint sprint) {
        require(properties, source, sprint);
        String effectiveQuery = compose(properties, source, sprint,
                sorted(properties.statuses()), sorted(properties.projects()));
        return new PilotScope(source, sprint, effectiveQuery, fingerprint(source, sprint, effectiveQuery));
    }

    public record PilotScope(
            ResolvedSource source,
            Sprint sprint,
            String effectiveQuery,
            String authorityFingerprint) {
    }

    private static void requireInput(PilotSourceProperties properties) {
        if (properties.source() == null) {
            throw new IllegalArgumentException(
                    "Team source is required: use a board or saved filter id or URL");
        }
        if (properties.sprintId() == null) {
            throw new IllegalArgumentException("A sprint id is required to compose the pilot scope");
        }
        if (properties.statuses().isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one eligible status is required to compose the pilot scope");
        }
    }

    private static void require(PilotSourceProperties properties, ResolvedSource source, Sprint sprint) {
        if (filterPart(source.query()).isEmpty()) {
            throw new IllegalArgumentException(
                    "Team source " + source.id() + " base query has no filter expression");
        }
        if (sprint == null || sprint.id() == null || sprint.id().isBlank()) {
            throw new IllegalArgumentException("A sprint is required to compose the pilot scope");
        }
        if (properties.statuses().isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one eligible status is required to compose the pilot scope");
        }
    }

    private static String compose(PilotSourceProperties properties, ResolvedSource source, Sprint sprint,
                                  List<String> statuses, List<String> projects) {
        String baseQuery = source.query();
        StringBuilder query = new StringBuilder();
        query.append("sprint = ").append(sprint.id());
        query.append(" AND status in (").append(quotedValues(statuses)).append(')');
        query.append(" AND (").append(filterPart(baseQuery)).append(')');
        if (properties.assignee() != null) {
            query.append(" AND assignee = ").append(jqlValue(properties.assignee()));
        }
        if (!projects.isEmpty()) {
            query.append(" AND project in (").append(quotedValues(projects)).append(')');
        }
        if (properties.label() != null) {
            query.append(" AND labels = ").append(jqlValue(properties.label()));
        }
        int orderBy = topLevelOrderByIndex(baseQuery);
        if (orderBy >= 0) {
            query.append(' ').append(baseQuery.substring(orderBy).strip());
        }
        return query.toString();
    }

    private static String filterPart(String jql) {
        int orderBy = topLevelOrderByIndex(jql);
        return (orderBy >= 0 ? jql.substring(0, orderBy) : jql).strip();
    }

    static int topLevelOrderByIndex(String jql) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < jql.length(); i++) {
            char c = jql.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(depth - 1, 0);
            } else if (depth == 0 && startsWithOrderBy(jql, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWithOrderBy(String jql, int index) {
        if (!jql.regionMatches(true, index, ORDER_BY, 0, ORDER_BY.length())) {
            return false;
        }
        boolean wordBefore = index == 0 || Character.isWhitespace(jql.charAt(index - 1));
        int after = index + ORDER_BY.length();
        boolean wordAfter = after >= jql.length() || Character.isWhitespace(jql.charAt(after));
        return wordBefore && wordAfter;
    }

    private static String quotedValues(List<String> values) {
        return values.stream()
                .map(PilotScopeResolver::jqlValue)
                .collect(Collectors.joining(", "));
    }

    private static String jqlValue(String value) {
        if (value.matches("\\w+\\(\\)")) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static String fingerprint(ResolvedSource source, Sprint sprint, String effectiveQuery) {
        String canonical = String.join("\n",
                String.valueOf(source.link()),
                source.id(),
                source.filterId(),
                sprint.id(),
                effectiveQuery);
        return Digests.sha256Hex(canonical);
    }
}
