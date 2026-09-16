package org.folio.factory.devfactory.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.connectors.jira.JiraRestConnector;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.folio.factory.devfactory.admission.TaskAdmissionService;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.InboxProperties;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryCatalog;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira key to admitted Developer Flow task: snapshot content and bounds, the
 * TaskRequest mapping, repository resolution through the trusted catalog, and
 * idempotent admission through the shared admission boundary.
 */
class JiraTaskIntakeTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path snapshots;
  private FakeJira jira;
  private PipelineRouter router;
  private ArtifactStore artifacts;
  private FakeAccess access;
  private JiraTaskService service;

  @BeforeEach
  void setUp() {
    jira = new FakeJira();
    router = mock(PipelineRouter.class);
    artifacts = mock(ArtifactStore.class);
    access = new FakeAccess();
    service = service(jira, new JiraSnapshotCollector(jira));
  }

  @Test
  void issueKeyBecomesJiraTaskRequestWithPreservedContextAndNoInventedAcceptanceCriteria() {
    jira.put(issue("MODSIDECAR-207", "Timeout not configurable", "The read timeout is ignored.\nh3. Notes",
        "MODSIDECAR", List.of(), List.of(link("Defines", "UXPROD-5894", true))));
    jira.comments.put("MODSIDECAR-207", comments(1));
    jira.linked.put("UXPROD-5894", "Umbrella feature description");

    JiraTaskSnapshot snapshot = new JiraSnapshotCollector(jira).collect("modsidecar-207");
    TaskRequest task = new JiraTaskMapper().toTaskRequest(snapshot, "jira-snapshot.json", "LOCAL_ONLY",
        null, null);

    assertThat(task.source()).isEqualTo(new TaskRequest.SourceIdentity("JIRA", "MODSIDECAR-207",
        "MODSIDECAR", null));
    assertThat(task.goal()).isEqualTo("MODSIDECAR-207: Timeout not configurable");
    assertThat(task.acceptanceCriteria()).isEmpty();
    assertThat(task.repository()).isNull();
    assertThat(task.baseRevision()).isNull();
    assertThat(task.baseRef()).isNull();
    assertThat(task.profileId()).isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_PI);
    assertThat(task.verificationPlanId()).isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_VERIFY);
    assertThat(task.runKey()).isEqualTo("default");
    assertThat(task.metadata().path("jiraSnapshotSha256").asString()).isEqualTo(snapshot.contentSha256());
    assertThat(task.metadata().has("fetchedAt")).isFalse();
    assertThat(task.rawTaskText())
        .contains("# Jira issue MODSIDECAR-207: Timeout not configurable")
        .contains("The read timeout is ignored.")
        .contains("MODSIDECAR-207 defines UXPROD-5894", "Umbrella feature description")
        .contains("comment body 0")
        .contains("status \"Open\" -> \"In Refinement\"")
        .contains("Jira has no separate acceptance-criteria field value");
    assertThat(snapshot.history()).extracting(JiraTaskSnapshot.HistoryEntry::field)
        .containsExactly("status");
  }

  @Test
  void explicitJiraAcceptanceCriteriaFieldIsPreservedVerbatim() {
    ObjectNode root = issue("MODSIDECAR-1", "Summary", "Description", "MODSIDECAR", List.of(), List.of());
    ((ObjectNode) root.path("fields")).put("customfield_20000", "Given X when Y then Z");
    ((ObjectNode) root.path("fields")).put("customfield_10044", 3.0);
    jira.put(root);

    JiraTaskSnapshot snapshot = new JiraSnapshotCollector(jira).collect("MODSIDECAR-1");
    TaskRequest task = new JiraTaskMapper().toTaskRequest(snapshot, null, "LOCAL_ONLY", null, null);

    assertThat(snapshot.storyPoints()).isEqualTo(3.0);
    assertThat(task.acceptanceCriteria()).containsExactly(new TaskRequest.AcceptanceCriterion(
        "JIRA-customfield_20000", "Given X when Y then Z", "JIRA_FIELD"));
  }

  @Test
  void commentsLinksHistoryAndTextAreHardBounded() {
    List<ObjectNode> links = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      links.add(link("Relates", "OTHER-" + (index + 1), index % 2 == 0));
    }
    ObjectNode root = issue("MODSIDECAR-9", "Big", "x".repeat(70_000), "MODSIDECAR", List.of(), links);
    ArrayNode histories = (ArrayNode) root.path("changelog").path("histories");
    for (int index = 0; index < 80; index++) {
      histories.addObject().put("created", String.format("2026-01-01T00:00:%02d.000+0000", index % 60))
          .putArray("items").addObject().put("field", "labels").put("fromString", "a")
          .put("toString", "b".repeat(2_000));
    }
    histories.addObject().put("created", "2026-02-01T00:00:00.000+0000")
        .putArray("items").addObject().put("field", "Rank").put("toString", "noise");
    jira.put(root);
    jira.comments.put("MODSIDECAR-9", comments(500));
    for (int index = 1; index <= 30; index++) {
      jira.linked.put("OTHER-" + index, "y".repeat(10_000));
    }

    JiraTaskSnapshot snapshot = new JiraSnapshotCollector(jira).collect("MODSIDECAR-9");

    assertThat(jira.commentLimits).containsExactly(JiraSnapshotCollector.MAX_COMMENTS);
    assertThat(snapshot.comments()).hasSize(JiraSnapshotCollector.MAX_COMMENTS);
    assertThat(snapshot.commentsTotal()).isEqualTo(500);
    assertThat(snapshot.links()).hasSize(JiraSnapshotCollector.MAX_LINKS);
    assertThat(snapshot.linksTruncated()).isTrue();
    assertThat(jira.linkedReads).hasSize(JiraSnapshotCollector.MAX_LINK_FETCHES);
    assertThat(snapshot.links().getLast().fetchError()).isEqualTo("NOT_FETCHED_LINK_BOUND");
    assertThat(snapshot.links().getFirst().description().length())
        .isLessThan(JiraSnapshotCollector.MAX_LINK_DESCRIPTION_CHARS + 100);
    assertThat(snapshot.links()).extracting(JiraTaskSnapshot.Link::direction).contains("OUTWARD", "INWARD");
    assertThat(snapshot.history()).hasSize(JiraSnapshotCollector.MAX_HISTORY);
    assertThat(snapshot.historyIncomplete()).isTrue();
    assertThat(snapshot.history()).noneMatch(entry -> entry.field().equals("Rank"));
    assertThat(snapshot.history().getFirst().to().length())
        .isLessThan(JiraSnapshotCollector.MAX_HISTORY_VALUE_CHARS + 100);
    assertThat(snapshot.descriptionTruncated()).isTrue();
    assertThat(new JiraTaskMapper().renderContext(snapshot, null).length())
        .isLessThanOrEqualTo(JiraTaskMapper.MAX_CONTEXT_CHARS + 200);
  }

  @Test
  void admittedJiraTaskResolvesCatalogRepositoryAtDefaultBranchAndStoresSnapshot() throws Exception {
    jira.put(issue("MODSIDECAR-207", "Timeout", "Body", "MODSIDECAR", List.of(), List.of()));
    UUID execution = UUID.randomUUID();
    when(router.routeAdmitted(any(), anyString())).thenReturn(List.of(execution));
    when(artifacts.getLatest(execution, JiraTaskService.SNAPSHOT_ARTIFACT)).thenReturn(Optional.empty());

    JiraTaskService.RunResult result = service.start(
        new JiraTaskService.RunRequest("MODSIDECAR-207", "DELIVER_PR", null, null));

    assertThat(result.outcome()).isEqualTo("ADMITTED");
    assertThat(result.executionId()).isEqualTo(execution);
    assertThat(result.existingExecution()).isFalse();
    assertThat(result.repository()).isEqualTo("folio-org/folio-module-sidecar");
    assertThat(result.baseRef()).isEqualTo("master");
    assertThat(result.baseRevision()).isEqualTo(SHA);
    assertThat(result.verificationPlanId()).isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_VERIFY);
    assertThat(access.defaultBranchLookups).containsExactly("folio-org/folio-module-sidecar");
    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), anyString());
    assertThat(event.getValue().type()).isEqualTo("file.inbox.pi");
    assertThat(event.getValue().source()).isEqualTo("jira:MODSIDECAR-207");
    JsonNode payload = event.getValue().payload();
    assertThat(payload.path("taskId").asString()).isEqualTo("MODSIDECAR-207");
    assertThat(payload.path("repoUrl").asString()).isEqualTo("https://github.com/folio-org/folio-module-sidecar.git");
    assertThat(payload.path("rawTaskText").asString()).contains("# Jira issue MODSIDECAR-207", "Body");
    assertThat(payload.path("resolvedIntent").path("profile").path("id").asString())
        .isEqualTo(TrustedProfileCatalog.JAVA_MAVEN_PI);
    assertThat(payload.path("resolvedIntent").path("task").path("deliveryMode").asString()).isEqualTo("DELIVER_PR");
    Path stored = Path.of(result.snapshotPath());
    assertThat(stored).startsWith(snapshots.resolve("MODSIDECAR-207")).exists();
    assertThat(JSON.readTree(Files.readString(stored)).path("contentSha256").asString())
        .isEqualTo(result.snapshotSha256());
    verify(artifacts).put(eq(execution), eq(JiraTaskService.SNAPSHOT_ARTIFACT), anyString(),
        eq("application/json"), eq("jira-intake"));
    assertThat(jira.writes).isEmpty();
  }

  @Test
  void repeatedIdenticalIntakeReplaysTheSameAdmissionAndSnapshot() {
    jira.put(issue("MODSIDECAR-207", "Timeout", "Body", "MODSIDECAR", List.of(), List.of()));
    UUID execution = UUID.randomUUID();
    when(router.routeAdmitted(any(), anyString())).thenReturn(List.of(execution));
    Artifact attached = mock(Artifact.class);
    when(artifacts.getLatest(execution, JiraTaskService.SNAPSHOT_ARTIFACT))
        .thenReturn(Optional.empty(), Optional.of(attached));
    JiraTaskService later = service(jira, new JiraSnapshotCollector(jira,
        Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)));
    JiraTaskService.RunRequest request = new JiraTaskService.RunRequest("MODSIDECAR-207", null, null, null);

    JiraTaskService.RunResult first = service.start(request);
    JiraTaskService.RunResult second = later.start(request);

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(router, times(2)).routeAdmitted(any(), keys.capture());
    assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    assertThat(second.snapshotSha256()).isEqualTo(first.snapshotSha256());
    assertThat(second.snapshotPath()).isEqualTo(first.snapshotPath());
    assertThat(second.existingExecution()).isTrue();
    verify(artifacts, times(1)).put(any(), any(), any(), any(), any());

    // A changed Jira issue or an explicit new runKey is a new admission revision.
    jira.put(issue("MODSIDECAR-207", "Timeout", "Body, clarified", "MODSIDECAR", List.of(), List.of()));
    service.start(request);
    service.start(new JiraTaskService.RunRequest("MODSIDECAR-207", null, null, "second-run"));
    ArgumentCaptor<String> allKeys = ArgumentCaptor.forClass(String.class);
    verify(router, times(4)).routeAdmitted(any(), allKeys.capture());
    List<String> revisions = allKeys.getAllValues().subList(2, 4);
    assertThat(revisions).doesNotHaveDuplicates().doesNotContain(keys.getAllValues().getFirst());
  }

  @Test
  void conflictingProjectAndComponentUseTheExistingDecisionFlow() {
    jira.put(issue("MODSIDECAR-5", "Cross-cutting", "Body", "MODSIDECAR", List.of("scheduler"), List.of()));
    UUID execution = UUID.randomUUID();
    when(router.routeAdmitted(any(), anyString())).thenReturn(List.of(execution));
    when(artifacts.getLatest(any(), any())).thenReturn(Optional.empty());

    JiraTaskService.RunResult result = service.start(
        new JiraTaskService.RunRequest("MODSIDECAR-5", null, null, null));

    assertThat(result.outcome()).isEqualTo("ADMITTED_NEEDS_DECISION");
    assertThat(result.code()).isEqualTo("REPOSITORY_SELECTION");
    assertThat(result.repository()).isNull();
    assertThat(result.repositoryCandidates())
        .containsExactly("folio-org/folio-module-sidecar", "folio-org/mod-scheduler");
    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), anyString());
    assertThat(event.getValue().type()).isEqualTo("file.inbox.pi.decision");
    assertThat(access.defaultBranchLookups).isEmpty();
  }

  @Test
  void unknownProjectIsBlockedWithoutCreatingAnExecution() {
    jira.put(issue("UIU-1", "UI work", "Body", "UIU", List.of(), List.of()));

    JiraTaskService.RunResult result = service.start(new JiraTaskService.RunRequest("UIU-1", null, null, null));

    assertThat(result.outcome()).isEqualTo("BLOCKED");
    assertThat(result.code()).isEqualTo("REPOSITORY_NOT_RESOLVED");
    assertThat(result.executionId()).isNull();
    verify(router, never()).routeAdmitted(any(), any());
    verify(artifacts, never()).put(any(), any(), any(), any(), any());
  }

  @Test
  void jiraFailuresAndInvalidRequestsAreExplicitAndCreateNoExecution() {
    jira.failure = new ConnectorNotConfiguredException("Jira connector not configured");
    assertIntakeFails("MODSIDECAR-1", JiraIntakeException.JIRA_NOT_CONFIGURED);
    jira.failure = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
        new byte[0], StandardCharsets.UTF_8);
    assertIntakeFails("MODSIDECAR-404", JiraIntakeException.ISSUE_NOT_FOUND);
    jira.failure = null;
    assertIntakeFails("not a key", JiraIntakeException.INVALID_REQUEST);
    assertThatThrownBy(() -> service.start(new JiraTaskService.RunRequest("MODSIDECAR-1", "PUSH", null, null)))
        .isInstanceOfSatisfying(JiraIntakeException.class,
            e -> assertThat(e.code()).isEqualTo(JiraIntakeException.INVALID_REQUEST));
    verify(router, never()).routeAdmitted(any(), any());
  }

  /**
   * The Jira credential is used only by the Factory-side connector. The trigger
   * payload is the only task data a sandbox or the coding runtime receives, and
   * neither it nor the stored snapshot carries the credential in any form.
   */
  @Test
  void jiraCredentialsNeverReachTheTaskPayloadOrSnapshot() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    String issueJson = JSON.writeValueAsString(issue("MODSIDECAR-207", "Timeout", "Body", "MODSIDECAR",
        List.of(), List.of()));
    server.expect(ExpectedCount.once(), requestTo("https://jira.example.org/rest/api/2/issue/MODSIDECAR-207?expand=changelog"))
        .andRespond(withSuccess(issueJson, MediaType.APPLICATION_JSON));
    server.expect(ExpectedCount.once(), requestTo("https://jira.example.org/rest/api/2/field"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server.expect(ExpectedCount.once(), requestTo("https://jira.example.org/rest/api/2/issue/MODSIDECAR-207/comment?maxResults=0"))
        .andRespond(withSuccess("{\"total\":0,\"comments\":[]}", MediaType.APPLICATION_JSON));
    server.expect(ExpectedCount.once(), requestTo("https://jira.example.org/rest/api/2/issue/MODSIDECAR-207/comment?startAt=0&maxResults=20"))
        .andRespond(withSuccess("{\"total\":0,\"comments\":[]}", MediaType.APPLICATION_JSON));
    String secret = "jira-api-token-SECRET-value";
    JiraConnector rest = new JiraRestConnector(
        new JiraProperties("https://jira.example.org", "bot@example.org", secret), builder);
    UUID execution = UUID.randomUUID();
    when(router.routeAdmitted(any(), anyString())).thenReturn(List.of(execution));
    when(artifacts.getLatest(any(), any())).thenReturn(Optional.empty());

    JiraTaskService.RunResult result = service(rest, new JiraSnapshotCollector(rest))
        .start(new JiraTaskService.RunRequest("MODSIDECAR-207", null, null, null));

    server.verify();
    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), anyString());
    String basic = HttpHeaders.encodeBasicAuth("bot@example.org", secret, StandardCharsets.UTF_8);
    String payload = JSON.writeValueAsString(event.getValue().payload());
    String snapshot = Files.readString(Path.of(result.snapshotPath()));
    for (String exposed : List.of(payload, snapshot)) {
      assertThat(exposed).doesNotContain(secret, basic, "bot@example.org", "apiToken");
    }
  }

  private void assertIntakeFails(String key, String code) {
    assertThatThrownBy(() -> service.start(new JiraTaskService.RunRequest(key, null, null, null)))
        .isInstanceOfSatisfying(JiraIntakeException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private JiraTaskService service(JiraConnector connector, JiraSnapshotCollector collector) {
    TaskResolutionService resolver = new TaskResolutionService(new RepositoryCatalog(), access,
        new TrustedProfileCatalog());
    InboxProperties inbox = new InboxProperties(true, snapshots.resolve("inbox"), 5000L, "file.inbox.pi");
    return new JiraTaskService(collector, new JiraSnapshotStore(snapshots), new JiraTaskMapper(),
        new TaskAdmissionService(resolver, router, inbox), artifacts);
  }

  private static ObjectNode issue(String key, String summary, String description, String project,
                                  List<String> components, List<ObjectNode> links) {
    ObjectNode root = JSON.createObjectNode();
    root.put("id", "10001");
    root.put("key", key);
    root.put("self", "https://jira.example.org/rest/api/2/issue/10001");
    ObjectNode fields = root.putObject("fields");
    fields.put("summary", summary);
    fields.put("description", description);
    fields.putObject("status").put("name", "In Refinement");
    fields.putObject("issuetype").put("name", "Bug");
    fields.putObject("project").put("key", project);
    fields.putArray("labels").add("back-end");
    ArrayNode componentNodes = fields.putArray("components");
    components.forEach(name -> componentNodes.addObject().put("name", name));
    fields.putArray("subtasks");
    ArrayNode linkNodes = fields.putArray("issuelinks");
    links.forEach(linkNodes::add);
    ObjectNode changelog = root.putObject("changelog");
    changelog.put("total", 2);
    ArrayNode histories = changelog.putArray("histories");
    ObjectNode status = histories.addObject();
    status.put("created", "2026-07-15T21:45:14.906+0000");
    status.putObject("author").put("displayName", "Reporter");
    status.putArray("items").addObject().put("field", "status").put("fromString", "Open")
        .put("toString", "In Refinement");
    ObjectNode assignee = histories.addObject();
    assignee.put("created", "2026-07-16T10:00:00.000+0000");
    assignee.putArray("items").addObject().put("field", "assignee").put("toString", "Someone");
    return root;
  }

  private static ObjectNode link(String type, String otherKey, boolean outward) {
    ObjectNode link = JSON.createObjectNode();
    link.putObject("type").put("name", type).put("outward", type.toLowerCase())
        .put("inward", "is " + type.toLowerCase() + " by");
    ObjectNode other = link.putObject(outward ? "outwardIssue" : "inwardIssue");
    other.put("key", otherKey);
    ObjectNode fields = other.putObject("fields");
    fields.put("summary", "Summary of " + otherKey);
    fields.putObject("status").put("name", "Open");
    fields.putObject("issuetype").put("name", "Feature");
    return link;
  }

  private static ObjectNode comments(int total) {
    ObjectNode page = JSON.createObjectNode();
    page.put("total", total);
    ArrayNode items = page.putArray("comments");
    for (int index = 0; index < Math.min(total, JiraSnapshotCollector.MAX_COMMENTS); index++) {
      items.addObject().put("id", String.valueOf(index)).put("body", "comment body " + index)
          .put("created", "2026-08-0" + (index % 9 + 1)).putObject("author").put("displayName", "Dev");
    }
    return page;
  }

  /** Read-only fake: every write is recorded and fails the test through {@link #writes}. */
  private static final class FakeJira implements JiraConnector {
    private final Map<String, JsonNode> issues = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> comments = new ConcurrentHashMap<>();
    private final Map<String, String> linked = new ConcurrentHashMap<>();
    private final List<Integer> commentLimits = new ArrayList<>();
    private final List<String> linkedReads = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private RuntimeException failure;

    void put(ObjectNode issue) {
      issues.put(issue.path("key").asString(), issue);
    }

    @Override public JiraIssue getIssue(String issueKey) {
      if (failure != null) throw failure;
      JsonNode raw = issues.get(issueKey);
      JsonNode fields = raw.path("fields");
      List<String> labels = new ArrayList<>();
      fields.path("labels").forEach(label -> labels.add(label.asString()));
      return new JiraIssue(issueKey, fields.path("summary").asString(), fields.path("description").asString(),
          fields.path("status").path("name").asString(), fields.path("issuetype").path("name").asString(),
          labels, raw);
    }

    @Override public JsonNode getComments(String issueKey, int limit) {
      commentLimits.add(limit);
      return comments.getOrDefault(issueKey, comments(0));
    }

    @Override public JiraIssue getLinkedIssue(String issueKey) {
      linkedReads.add(issueKey);
      return new JiraIssue(issueKey, "", linked.getOrDefault(issueKey, ""), "", "", List.of(),
          JSON.createObjectNode());
    }

    @Override public JsonNode getFields() {
      ArrayNode fields = JSON.createArrayNode();
      fields.addObject().put("id", "customfield_10044").put("name", "Story Points");
      fields.addObject().put("id", "customfield_20000").put("name", "Acceptance Criteria");
      return fields;
    }

    @Override public void addComment(String issueKey, String body) {
      writes.add("comment " + issueKey);
      throw new AssertionError("Jira intake must not write comments");
    }

    @Override public void transitionIssue(String issueKey, String transitionName) {
      writes.add("transition " + issueKey);
      throw new AssertionError("Jira intake must not transition issues");
    }
  }

  private static final class FakeAccess implements RepositoryAccess {
    private static final byte[] POM = "<project><properties><java.version>21</java.version></properties></project>"
        .getBytes(StandardCharsets.UTF_8);
    private final List<String> defaultBranchLookups = new ArrayList<>();

    @Override public String defaultBranch(String slug) {
      defaultBranchLookups.add(slug);
      return "master";
    }

    @Override public String resolveBranch(String slug, String branch) {
      assertThat(branch).isEqualTo("master");
      return SHA;
    }

    @Override public String verifyCommit(String slug, String sha) {
      return sha;
    }

    @Override public Optional<byte[]> readFile(String slug, String sha, String path, int maxBytes) {
      return "pom.xml".equals(path) ? Optional.of(POM) : Optional.empty();
    }
  }
}
