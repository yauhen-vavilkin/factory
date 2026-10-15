package org.folio.factory.devfactory.intake;

import org.folio.factory.connectors.jira.JiraIssue;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded, normalized view of one Jira issue. Every limit that cuts content is
 * listed in {@code truncated}; linked issues are read from the issue itself only,
 * never fetched.
 */
public final class IssueSnapshot {

    public static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,31}-[1-9][0-9]{0,8}");

    static final int MAX_SUMMARY = 500;
    static final int MAX_DESCRIPTION = 20_000;
    static final int MAX_LIST = 20;
    static final int MAX_NAME = 100;
    static final int MAX_COMMENTS = 10;
    static final int MAX_COMMENT = 2_000;

    private IssueSnapshot() {
    }

    public static String projectKey(String issueKey) {
        return issueKey.substring(0, issueKey.lastIndexOf('-'));
    }

    public static Map<String, Object> of(JiraIssue issue) {
        List<String> truncated = new ArrayList<>();
        JsonNode fields = issue.raw() == null ? null : issue.raw().path("fields");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", issue.key());
        snapshot.put("summary", cut(issue.summary(), MAX_SUMMARY, "summary", truncated));
        snapshot.put("status", cut(issue.status(), MAX_NAME, "status", truncated));
        snapshot.put("type", cut(issue.issueType(), MAX_NAME, "type", truncated));
        snapshot.put("labels", names(issue.labels(), "labels", truncated));
        snapshot.put("components", fields == null ? List.of() : components(fields, truncated));
        snapshot.put("description", cut(description(issue), MAX_DESCRIPTION, "description", truncated));
        snapshot.put("comments", fields == null ? List.of() : comments(fields, truncated));
        snapshot.put("links", fields == null ? List.of() : links(fields, truncated));
        snapshot.put("truncated", truncated);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static List<String> components(Map<String, Object> snapshot) {
        return (List<String>) snapshot.get("components");
    }

    private static String description(JiraIssue issue) {
        String description = issue.description();
        // The connector renders a missing description as the JSON literal "null".
        return description == null || "null".equals(description) ? "" : description.strip();
    }

    private static List<String> components(JsonNode fields, List<String> truncated) {
        List<String> names = new ArrayList<>();
        fields.path("components").forEach(c -> names.add(c.path("name").asString("")));
        return names(names, "components", truncated);
    }

    private static List<String> names(List<String> values, String field, List<String> truncated) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            if (result.size() == MAX_LIST) {
                truncated.add(field + ": kept " + MAX_LIST + " of " + values.size());
                break;
            }
            result.add(cut(value, MAX_NAME, field, truncated));
        }
        return result;
    }

    private static List<Map<String, Object>> comments(JsonNode fields, List<String> truncated) {
        List<JsonNode> all = new ArrayList<>();
        fields.path("comment").path("comments").forEach(all::add);
        int from = Math.max(0, all.size() - MAX_COMMENTS);
        if (from > 0) {
            truncated.add("comments: kept last " + MAX_COMMENTS + " of " + all.size());
        }
        List<Map<String, Object>> comments = new ArrayList<>();
        for (JsonNode comment : all.subList(from, all.size())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("author", cut(comment.path("author").path("displayName").asString(""), MAX_NAME,
                    "comment author", truncated));
            row.put("created", cut(comment.path("created").asString(""), MAX_NAME, "comment created", truncated));
            row.put("body", cut(comment.path("body").isString() ? comment.path("body").asString()
                    : comment.path("body").toString(), MAX_COMMENT, "comment body", truncated));
            comments.add(row);
        }
        return comments;
    }

    private static List<Map<String, Object>> links(JsonNode fields, List<String> truncated) {
        List<Map<String, Object>> links = new ArrayList<>();
        JsonNode all = fields.path("issuelinks");
        for (JsonNode link : all) {
            if (links.size() == MAX_LIST) {
                truncated.add("links: kept " + MAX_LIST + " of " + all.size());
                break;
            }
            boolean outward = link.has("outwardIssue");
            JsonNode other = outward ? link.path("outwardIssue") : link.path("inwardIssue");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("relation", cut(link.path("type").path(outward ? "outward" : "inward").asString(""),
                    MAX_NAME, "link relation", truncated));
            row.put("key", cut(other.path("key").asString(""), MAX_NAME, "link key", truncated));
            row.put("summary", cut(other.path("fields").path("summary").asString(""), MAX_SUMMARY,
                    "link summary", truncated));
            row.put("status", cut(other.path("fields").path("status").path("name").asString(""), MAX_NAME,
                    "link status", truncated));
            links.add(row);
        }
        return links;
    }

    private static String cut(String value, int max, String field, List<String> truncated) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        truncated.add(field + ": kept " + max + " of " + value.length() + " characters");
        return value.substring(0, max) + "\n[truncated]";
    }
}
