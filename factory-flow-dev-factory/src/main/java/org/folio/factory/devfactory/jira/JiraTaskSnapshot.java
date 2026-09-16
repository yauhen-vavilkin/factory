package org.folio.factory.devfactory.jira;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Immutable, normalized first-order Jira context of one task: the root issue,
 * its newest comments, requirement/scope/status history, parent, subtasks and
 * direct links. No link is followed beyond one hop. Every list is bounded and
 * says when it was truncated. {@code contentSha256} covers everything except
 * {@code fetchedAt} and {@code raw}, so an unchanged issue fetched twice has the
 * same identity.
 */
public record JiraTaskSnapshot(
    String schema,
    String issueKey,
    String issueId,
    String sourceUrl,
    String fetchedAt,
    String summary,
    String description,
    boolean descriptionTruncated,
    String status,
    String issueType,
    String project,
    List<String> components,
    List<String> labels,
    Double storyPoints,
    List<Field> acceptanceCriteriaFields,
    IssueRef parent,
    List<IssueRef> subtasks,
    List<Link> links,
    boolean linksTruncated,
    List<Comment> comments,
    int commentsTotal,
    List<HistoryEntry> history,
    int historyTotal,
    boolean historyIncomplete,
    String contentSha256,
    JsonNode raw) {

  public static final String SCHEMA = "JiraTaskSnapshot/v1";

  public JiraTaskSnapshot {
    components = List.copyOf(components);
    labels = List.copyOf(labels);
    acceptanceCriteriaFields = List.copyOf(acceptanceCriteriaFields);
    subtasks = List.copyOf(subtasks);
    links = List.copyOf(links);
    comments = List.copyOf(comments);
    history = List.copyOf(history);
  }

  public JiraTaskSnapshot withIdentity(String fetchedAt, String contentSha256, JsonNode raw) {
    return new JiraTaskSnapshot(schema, issueKey, issueId, sourceUrl, fetchedAt, summary, description,
        descriptionTruncated, status, issueType, project, components, labels, storyPoints,
        acceptanceCriteriaFields, parent, subtasks, links, linksTruncated, comments, commentsTotal,
        history, historyTotal, historyIncomplete, contentSha256, raw);
  }

  /** A named Jira field value preserved verbatim (for example an explicit acceptance-criteria field). */
  public record Field(String id, String name, String value) {
  }

  public record IssueRef(String key, String summary, String status, String issueType) {
  }

  /**
   * One direct issue link. {@code direction} is OUTWARD or INWARD, {@code relation}
   * the phrase read from the root issue (for example "defines" or "is blocked by").
   * The linked issue's description is fetched once and bounded; a linked issue
   * the credentials cannot read keeps its metadata and records {@code fetchError}.
   */
  public record Link(String type, String direction, String relation, String key, String summary,
                     String status, String issueType, String description, String fetchError) {
  }

  public record Comment(String id, String author, String created, String updated, String body,
                        boolean truncated) {
  }

  public record HistoryEntry(String created, String author, String field, String from, String to) {
  }
}
