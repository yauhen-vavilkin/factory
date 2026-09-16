package org.folio.factory.devfactory.jira;

import java.util.List;
import org.folio.factory.devfactory.contract.CanonicalJson;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira snapshot to Developer Flow task contract. The mapping adds no
 * requirement of its own: the goal is the issue summary, acceptance criteria
 * exist only when Jira has an explicit acceptance-criteria field, and the
 * complete bounded issue context travels as the task text the coding runtime
 * reads. Repository selection is left to the trusted catalog through the
 * issue's project and (single) component, and the verification plan to the
 * operator or the trusted resolution: Jira never selects one.
 *
 * <p>Task identity is the canonical issue key plus a digest of the root
 * requirement fields (summary, description, explicit acceptance criteria).
 * The full snapshot digest, the requested key and the source URL are
 * provenance: Jira status, labels, links, comments and history do not make a
 * new task.
 */
public final class JiraTaskMapper {
  public static final String SOURCE_TYPE = "JIRA";
  public static final String DEFAULT_RUN_KEY = "default";
  static final int MAX_CONTEXT_CHARS = 32_000;
  private static final int DESCRIPTION_BUDGET = 16_000;
  private static final int ACCEPTANCE_BUDGET = 4_000;
  private static final int LINKS_BUDGET = 4_000;
  private static final int COMMENTS_BUDGET = 5_000;
  private static final int HISTORY_BUDGET = 2_000;
  private static final int LINK_EXCERPT_CHARS = 600;
  static final String NOTES = "The authoritative task source is the Jira issue in the task text "
      + "(description, explicit acceptance criteria when Jira has them, direct links, comments, history). "
      + "Do not add requirements the issue does not state. Factory performs independent verification "
      + "after coding.";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final JsonMapper json = JSON;

  public TaskRequest toTaskRequest(JiraTaskSnapshot snapshot, String snapshotLocator, String deliveryMode,
                                   String baseRef, String runKey, String verificationPlanId) {
    ObjectNode metadata = json.createObjectNode();
    metadata.put("jiraIssueKey", snapshot.issueKey());
    metadata.put("jiraRequirementSha256", requirementSha256(snapshot));
    ObjectNode provenance = metadata.putObject(TaskResolutionService.PROVENANCE);
    provenance.put("jiraRequestedKey", snapshot.requestedKey());
    putNullable(provenance, "jiraSourceUrl", snapshot.sourceUrl());
    provenance.put("jiraSnapshotSchema", snapshot.schema());
    provenance.put("jiraSnapshotSha256", snapshot.contentSha256());
    List<TaskRequest.AcceptanceCriterion> criteria = snapshot.acceptanceCriteriaFields().stream()
        .map(field -> new TaskRequest.AcceptanceCriterion("JIRA-" + field.id(), field.value(), "JIRA_FIELD"))
        .toList();
    String component = snapshot.components().size() == 1 ? snapshot.components().getFirst() : null;
    return new TaskRequest(TaskRequest.CURRENT_SCHEMA_VERSION,
        new TaskRequest.SourceIdentity(SOURCE_TYPE, snapshot.issueKey(), blankToNull(snapshot.project()), component),
        null, null, blankToNull(baseRef),
        TrustedProfileCatalog.JAVA_MAVEN_PI, blankToNull(verificationPlanId),
        runKey == null || runKey.isBlank() ? DEFAULT_RUN_KEY : runKey.trim(),
        deliveryMode == null || deliveryMode.isBlank() ? "LOCAL_ONLY" : deliveryMode.trim(),
        metadata, snapshot.issueKey() + ": " + snapshot.summary(), criteria, json.createObjectNode(),
        NOTES, renderContext(snapshot, snapshotLocator), false,
        JiraTaskSufficiency.missingRequirements(snapshot).stream().toList());
  }

  /** Digest of what the root issue requires; operational Jira state is deliberately not part of it. */
  static String requirementSha256(JiraTaskSnapshot snapshot) {
    ObjectNode requirement = JSON.createObjectNode();
    requirement.put("schema", "JiraRequirement/v1");
    requirement.put("issueKey", snapshot.issueKey());
    requirement.put("summary", snapshot.summary());
    putNullable(requirement, "description", snapshot.description());
    ArrayNode criteria = requirement.putArray("acceptanceCriteriaFields");
    snapshot.acceptanceCriteriaFields().forEach(field ->
        criteria.addObject().put("id", field.id()).put("value", field.value()));
    return CanonicalJson.sha256(requirement);
  }

  /** Bounded, readable Jira context for the coding runtime; the full snapshot stays separate. */
  public String renderContext(JiraTaskSnapshot s, String snapshotLocator) {
    StringBuilder out = new StringBuilder();
    out.append("# Jira issue ").append(s.issueKey()).append(": ").append(s.summary()).append("\n\n");
    out.append("Source: ").append(s.sourceUrl() == null ? s.issueKey() : s.sourceUrl())
        .append(" (Factory snapshot ").append(s.schema()).append(" sha256 ").append(s.contentSha256());
    if (snapshotLocator != null) {
      out.append(", stored as ").append(snapshotLocator);
    }
    out.append(")\n");
    out.append("Type: ").append(s.issueType()).append(" | Status: ").append(s.status())
        .append(" | Project: ").append(s.project())
        .append(" | Components: ").append(s.components().isEmpty() ? "none" : String.join(", ", s.components()))
        .append(" | Labels: ").append(s.labels().isEmpty() ? "none" : String.join(", ", s.labels()))
        .append(" | Story points: ").append(s.storyPoints() == null ? "not set" : s.storyPoints())
        .append('\n');
    if (s.parent() != null) {
      out.append("Parent: ").append(ref(s.parent())).append('\n');
    }
    if (!s.subtasks().isEmpty()) {
      out.append("Subtasks: ").append(String.join("; ", s.subtasks().stream().map(JiraTaskMapper::ref).toList()))
          .append('\n');
    }

    out.append("\n## Description\n\n")
        .append(budget(s.description() == null || s.description().isBlank() ? "(empty)" : s.description(),
            DESCRIPTION_BUDGET)).append('\n');

    out.append("\n## Explicit acceptance criteria (Jira field)\n\n");
    if (s.acceptanceCriteriaFields().isEmpty()) {
      out.append("Jira has no separate acceptance-criteria field value for this issue. Use only what the "
          + "description and comments state.\n");
    } else {
      StringBuilder acceptance = new StringBuilder();
      s.acceptanceCriteriaFields().forEach(field ->
          acceptance.append("### ").append(field.name()).append("\n").append(field.value()).append("\n"));
      out.append(budget(acceptance.toString(), ACCEPTANCE_BUDGET));
    }

    out.append("\n## Direct linked issues (one hop only)\n\n");
    if (s.links().isEmpty()) {
      out.append("None.\n");
    } else {
      StringBuilder links = new StringBuilder();
      for (JiraTaskSnapshot.Link link : s.links()) {
        links.append("- ").append(s.issueKey()).append(' ').append(link.relation()).append(' ')
            .append(link.key()).append(" [").append(link.issueType()).append(", ").append(link.status())
            .append("]: ").append(link.summary()).append('\n');
        if (link.description() != null && !link.description().isBlank()) {
          links.append("  ").append(excerpt(link.description())).append('\n');
        }
      }
      if (s.linksTruncated()) {
        links.append("- (more links exist; Factory kept the first ").append(s.links().size()).append(")\n");
      }
      out.append(budget(links.toString(), LINKS_BUDGET));
    }

    out.append("\n## Comments (").append(s.comments().size()).append(" newest of ").append(s.commentsTotal())
        .append(", oldest first)\n\n");
    if (s.comments().isEmpty()) {
      out.append("None.\n");
    } else {
      StringBuilder comments = new StringBuilder();
      // Newest last so a budget cut drops the oldest comments first.
      for (JiraTaskSnapshot.Comment comment : s.comments()) {
        comments.append("### ").append(comment.author()).append(", ").append(comment.created()).append('\n')
            .append(comment.body()).append("\n\n");
      }
      out.append(budgetKeepTail(comments.toString(), COMMENTS_BUDGET));
    }

    out.append("\n## Requirement, scope and status history (newest first)\n\n");
    if (s.history().isEmpty()) {
      out.append("None.\n");
    } else {
      StringBuilder history = new StringBuilder();
      s.history().forEach(entry -> history.append("- ").append(entry.created()).append(' ')
          .append(entry.author()).append(": ").append(entry.field()).append(" \"")
          .append(oneLine(entry.from())).append("\" -> \"").append(oneLine(entry.to())).append("\"\n"));
      if (s.historyIncomplete()) {
        history.append("- (older history exists and is not shown)\n");
      }
      out.append(budget(history.toString(), HISTORY_BUDGET));
    }
    return budget(out.toString(), MAX_CONTEXT_CHARS);
  }

  private static String ref(JiraTaskSnapshot.IssueRef ref) {
    return ref.key() + " [" + ref.issueType() + ", " + ref.status() + "] " + ref.summary();
  }

  private static String excerpt(String text) {
    String single = oneLine(text);
    return single.length() <= LINK_EXCERPT_CHARS ? single : single.substring(0, LINK_EXCERPT_CHARS) + " [...]";
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replaceAll("\\s+", " ").trim();
  }

  static String budget(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "\n[... truncated by Factory; the full "
        + "text is in the stored Jira snapshot]\n";
  }

  private static String budgetKeepTail(String text, int max) {
    return text.length() <= max ? text : "[... older comment text truncated by Factory]\n"
        + text.substring(text.length() - max);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
