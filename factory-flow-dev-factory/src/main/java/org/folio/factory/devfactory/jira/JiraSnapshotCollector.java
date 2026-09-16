package org.folio.factory.devfactory.jira;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.devfactory.contract.CanonicalJson;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Factory-owned Jira research policy: deterministically gathers the useful
 * first-order context of one issue through the read-only connector. It never
 * follows a link of a linked issue and never calls a model. All expansion is
 * hard-bounded so a pathological issue cannot produce an unbounded snapshot.
 */
public final class JiraSnapshotCollector {
  public static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,31}-[1-9][0-9]{0,8}");
  static final int MAX_COMMENTS = 20;
  static final int MAX_HISTORY = 50;
  static final int MAX_LINKS = 20;
  static final int MAX_LINK_FETCHES = 10;
  static final int MAX_SUBTASKS = 30;
  static final int MAX_DESCRIPTION_CHARS = 64_000;
  static final int MAX_COMMENT_CHARS = 8_000;
  static final int MAX_LINK_DESCRIPTION_CHARS = 4_000;
  static final int MAX_HISTORY_VALUE_CHARS = 500;
  static final int MAX_RAW_BYTES = 256 * 1024;

  /** Changelog fields that describe requirements, scope or status; everything else is noise here. */
  private static final Set<String> RELEVANT_HISTORY_FIELDS = Set.of("summary", "description", "status",
      "resolution", "labels", "component", "fix version", "version", "issuetype", "link", "parent",
      "epic link", "priority", "sprint", "release");
  private static final Set<String> STORY_POINT_FIELD_NAMES = Set.of("story points", "story point estimate");

  private final JiraConnector jira;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();
  private volatile JsonNode fieldCatalog;

  public JiraSnapshotCollector(JiraConnector jira) {
    this(jira, Clock.systemUTC());
  }

  JiraSnapshotCollector(JiraConnector jira, Clock clock) {
    this.jira = jira;
    this.clock = clock;
  }

  public static String normalizeKey(String issueKey) {
    String key = issueKey == null ? "" : issueKey.trim().toUpperCase(Locale.ROOT);
    if (!ISSUE_KEY.matcher(key).matches()) {
      throw new JiraIntakeException(JiraIntakeException.INVALID_REQUEST,
          "issueKey must look like PROJECT-123, got '" + issueKey + "'");
    }
    return key;
  }

  public JiraTaskSnapshot collect(String issueKey) {
    String requestedKey = normalizeKey(issueKey);
    JiraIssue issue = read(requestedKey, () -> jira.getIssue(requestedKey));
    JsonNode raw = issue.raw();
    // A moved or renamed issue answers under its new key: that key is the
    // canonical identity, the requested one is only an alias.
    String key = canonicalKey(issue, raw, requestedKey);
    JsonNode fields = raw.path("fields");
    String description = text(fields.path("description"));

    List<JiraTaskSnapshot.Field> acceptance = new ArrayList<>();
    Double storyPoints = null;
    for (JsonNode field : fieldCatalog()) {
      String id = field.path("id").asString("");
      String name = field.path("name").asString("");
      JsonNode value = fields.path(id);
      if (id.isBlank() || value.isMissingNode() || value.isNull()) {
        continue;
      }
      String lower = name.toLowerCase(Locale.ROOT);
      if (storyPoints == null && STORY_POINT_FIELD_NAMES.contains(lower) && value.isNumber()) {
        storyPoints = value.asDouble();
      }
      if (lower.contains("acceptance criteria")) {
        String content = text(value);
        if (!content.isBlank()) {
          acceptance.add(new JiraTaskSnapshot.Field(id, name, bounded(content, MAX_DESCRIPTION_CHARS)));
        }
      }
    }

    JsonNode parentNode = fields.path("parent");
    JiraTaskSnapshot.IssueRef parent = parentNode.isObject() ? ref(parentNode) : null;
    List<JiraTaskSnapshot.IssueRef> subtasks = new ArrayList<>();
    for (JsonNode subtask : fields.path("subtasks")) {
      if (subtasks.size() == MAX_SUBTASKS) {
        break;
      }
      subtasks.add(ref(subtask));
    }

    List<JiraTaskSnapshot.Link> links = new ArrayList<>();
    JsonNode linkNodes = fields.path("issuelinks");
    for (JsonNode link : linkNodes) {
      if (links.size() == MAX_LINKS) {
        break;
      }
      links.add(link(link, links.size() < MAX_LINK_FETCHES));
    }

    JsonNode commentPage = read(key, () -> jira.getComments(key, MAX_COMMENTS));
    List<JiraTaskSnapshot.Comment> comments = new ArrayList<>();
    for (JsonNode comment : commentPage.path("comments")) {
      String body = text(comment.path("body"));
      comments.add(new JiraTaskSnapshot.Comment(comment.path("id").asString(""),
          comment.path("author").path("displayName").asString(""),
          comment.path("created").asString(""), comment.path("updated").asString(""),
          bounded(body, MAX_COMMENT_CHARS), body.length() > MAX_COMMENT_CHARS));
    }

    JsonNode changelog = raw.path("changelog");
    List<JiraTaskSnapshot.HistoryEntry> relevant = new ArrayList<>();
    for (JsonNode history : changelog.path("histories")) {
      for (JsonNode item : history.path("items")) {
        String field = item.path("field").asString("");
        if (!RELEVANT_HISTORY_FIELDS.contains(field.toLowerCase(Locale.ROOT))
            && !field.toLowerCase(Locale.ROOT).contains("acceptance")
            && !field.toLowerCase(Locale.ROOT).contains("point")) {
          continue;
        }
        relevant.add(new JiraTaskSnapshot.HistoryEntry(history.path("created").asString(""),
            history.path("author").path("displayName").asString(""), field,
            bounded(item.path("fromString").asString(""), MAX_HISTORY_VALUE_CHARS),
            bounded(item.path("toString").asString(""), MAX_HISTORY_VALUE_CHARS)));
      }
    }
    relevant.sort((left, right) -> right.created().compareTo(left.created()));
    List<JiraTaskSnapshot.HistoryEntry> history = relevant.subList(0, Math.min(MAX_HISTORY, relevant.size()));
    boolean historyIncomplete = relevant.size() > MAX_HISTORY
        || changelog.path("total").asInt(0) > changelog.path("histories").size();

    List<String> components = new ArrayList<>();
    fields.path("components").forEach(component -> components.add(component.path("name").asString("")));

    JiraTaskSnapshot draft = new JiraTaskSnapshot(JiraTaskSnapshot.SCHEMA, key, requestedKey,
        raw.path("id").asString(""), browseUrl(raw, key), null, issue.summary(),
        bounded(description, MAX_DESCRIPTION_CHARS), description.length() > MAX_DESCRIPTION_CHARS,
        issue.status(), issue.issueType(), fields.path("project").path("key").asString(""),
        components, issue.labels(), storyPoints, acceptance, parent, subtasks, links,
        linkNodes.size() > MAX_LINKS, comments, commentPage.path("total").asInt(comments.size()),
        history, relevant.size(), historyIncomplete, null, null);
    ObjectNode identity = json.valueToTree(draft);
    identity.remove(List.of("requestedKey", "fetchedAt", "contentSha256", "raw"));
    return draft.withIdentity(Instant.now(clock).toString(), CanonicalJson.sha256(identity),
        boundedRaw(raw));
  }

  private static String canonicalKey(JiraIssue issue, JsonNode raw, String requestedKey) {
    String returned = issue.key() == null || issue.key().isBlank() ? raw.path("key").asString("") : issue.key();
    if (returned.isBlank()) {
      return requestedKey;
    }
    String canonical = returned.trim().toUpperCase(Locale.ROOT);
    if (!ISSUE_KEY.matcher(canonical).matches()) {
      throw new JiraIntakeException(JiraIntakeException.JIRA_UNAVAILABLE,
          "Jira returned an invalid issue key for " + requestedKey);
    }
    return canonical;
  }

  private JiraTaskSnapshot.Link link(JsonNode link, boolean fetch) {
    boolean outward = link.has("outwardIssue");
    JsonNode other = outward ? link.path("outwardIssue") : link.path("inwardIssue");
    JsonNode type = link.path("type");
    String otherKey = other.path("key").asString("");
    String description = null;
    String error = null;
    if (fetch && !otherKey.isBlank()) {
      try {
        description = bounded(jira.getLinkedIssue(otherKey).description(), MAX_LINK_DESCRIPTION_CHARS);
      } catch (RestClientException e) {
        // A restricted or vanished linked issue keeps its link metadata.
        error = e.getClass().getSimpleName() + ": " + bounded(e.getMessage(), 200);
      }
    } else if (!otherKey.isBlank()) {
      error = "NOT_FETCHED_LINK_BOUND";
    }
    JiraTaskSnapshot.IssueRef ref = ref(other);
    return new JiraTaskSnapshot.Link(type.path("name").asString(""), outward ? "OUTWARD" : "INWARD",
        type.path(outward ? "outward" : "inward").asString(""), ref.key(), ref.summary(), ref.status(),
        ref.issueType(), description, error);
  }

  private static JiraTaskSnapshot.IssueRef ref(JsonNode issue) {
    JsonNode fields = issue.path("fields");
    return new JiraTaskSnapshot.IssueRef(issue.path("key").asString(""),
        fields.path("summary").asString(""), fields.path("status").path("name").asString(""),
        fields.path("issuetype").path("name").asString(""));
  }

  private JsonNode fieldCatalog() {
    JsonNode catalog = fieldCatalog;
    if (catalog == null) {
      catalog = read("field metadata", jira::getFields);
      fieldCatalog = catalog;
    }
    return catalog;
  }

  /** The raw issue minus the parts already normalized (comments, changelog) and bulky noise. */
  private JsonNode boundedRaw(JsonNode raw) {
    ObjectNode copy = (ObjectNode) raw.deepCopy();
    copy.remove("changelog");
    JsonNode fields = copy.path("fields");
    if (fields instanceof ObjectNode object) {
      object.remove(List.of("comment", "worklog", "attachment"));
    }
    if (json.writeValueAsBytes(copy).length > MAX_RAW_BYTES) {
      ObjectNode omitted = json.createObjectNode();
      omitted.put("omitted", "raw issue exceeds " + MAX_RAW_BYTES + " bytes");
      return omitted;
    }
    return copy;
  }

  private static String browseUrl(JsonNode raw, String key) {
    String self = raw.path("self").asString("");
    int api = self.indexOf("/rest/api/");
    return api < 0 ? null : self.substring(0, api) + "/browse/" + key;
  }

  private static String text(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) {
      return "";
    }
    return value.isString() ? value.asString() : value.toString();
  }

  static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max) + "\n[truncated by Factory]";
  }

  private static <T> T read(String what, java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (ConnectorNotConfiguredException e) {
      throw new JiraIntakeException(JiraIntakeException.JIRA_NOT_CONFIGURED, e.getMessage(), e);
    } catch (HttpClientErrorException.NotFound e) {
      throw new JiraIntakeException(JiraIntakeException.ISSUE_NOT_FOUND,
          "Jira issue " + what + " does not exist or is not visible to the configured Jira access", e);
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
      throw new JiraIntakeException(JiraIntakeException.JIRA_ACCESS_DENIED,
          "Jira refused access to " + what + " (HTTP " + e.getStatusCode().value() + ")", e);
    } catch (RestClientException e) {
      throw new JiraIntakeException(JiraIntakeException.JIRA_UNAVAILABLE,
          "Jira read of " + what + " failed: " + bounded(e.getMessage(), 300), e);
    }
  }
}
