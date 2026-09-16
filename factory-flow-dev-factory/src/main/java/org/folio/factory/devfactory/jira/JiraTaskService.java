package org.folio.factory.devfactory.jira;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.admission.TaskAdmissionService;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.GitRefs;
import org.folio.factory.devfactory.resolution.RepositoryAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the normal Developer Flow from a Jira issue key: fetch and store the
 * first-order Jira snapshot, map it to a {@link TaskRequest}, and admit it
 * through the same {@link TaskAdmissionService} as the file inbox. The single
 * entry point for the REST API, the UI form and the CLI. It only reads Jira.
 */
public final class JiraTaskService {
  public static final String SNAPSHOT_ARTIFACT = "jira-snapshot.json";
  private static final Logger log = LoggerFactory.getLogger(JiraTaskService.class);

  private final JiraSnapshotCollector collector;
  private final JiraSnapshotStore store;
  private final JiraTaskMapper mapper;
  private final TaskAdmissionService admission;
  private final ArtifactStore artifacts;

  public JiraTaskService(JiraSnapshotCollector collector, JiraSnapshotStore store, JiraTaskMapper mapper,
                         TaskAdmissionService admission, ArtifactStore artifacts) {
    this.collector = collector;
    this.store = store;
    this.mapper = mapper;
    this.admission = admission;
    this.artifacts = artifacts;
  }

  public record RunRequest(String issueKey, String deliveryMode, String baseRef, String runKey) {
  }

  /**
   * Result of one intake. {@code outcome} is ADMITTED, ADMITTED_NEEDS_DECISION,
   * BLOCKED or NO_MATCHING_FLOW; only the first two carry an execution id.
   * {@code existingExecution} is true when the same snapshot and runKey had
   * already been admitted and the earlier execution was returned.
   */
  public record RunResult(String issueKey, String outcome, String code, String message, UUID executionId,
                          boolean existingExecution, String repository, List<String> repositoryCandidates,
                          String baseRef, String baseRevision, String verificationPlanId,
                          String snapshotSha256, String snapshotPath, String semanticTaskHash) {
    public boolean admitted() {
      return executionId != null;
    }
  }

  public RunResult start(RunRequest request) {
    String deliveryMode = request.deliveryMode() == null || request.deliveryMode().isBlank()
        ? "LOCAL_ONLY" : request.deliveryMode().trim();
    if (!"LOCAL_ONLY".equals(deliveryMode) && !"DELIVER_PR".equals(deliveryMode)) {
      throw new JiraIntakeException(JiraIntakeException.INVALID_REQUEST,
          "deliveryMode must be LOCAL_ONLY or DELIVER_PR");
    }
    String baseRef = request.baseRef() == null || request.baseRef().isBlank() ? null : request.baseRef().trim();
    if (baseRef != null && (!GitRefs.isValidBranchName(baseRef) || GitRefs.isRawCommitId(baseRef))) {
      throw new JiraIntakeException(JiraIntakeException.INVALID_REQUEST,
          "baseRef must be a safe branch name, not a commit SHA");
    }
    String key = JiraSnapshotCollector.normalizeKey(request.issueKey());

    JiraTaskSnapshot snapshot = collector.collect(key);
    JiraSnapshotStore.Stored stored = store.store(snapshot);
    TaskRequest task = mapper.toTaskRequest(snapshot, SNAPSHOT_ARTIFACT, deliveryMode, baseRef,
        request.runKey());

    TaskAdmissionService.Admission result;
    try {
      result = admission.admit(task, "jira:" + key);
    } catch (RepositoryAccessException e) {
      throw new JiraIntakeException(JiraIntakeException.REPOSITORY_UNAVAILABLE,
          "repository lookup failed; retry later: " + e.getMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new JiraIntakeException(JiraIntakeException.INVALID_REQUEST, e.getMessage(), e);
    }
    ResolvedIntent intent = result.intent();
    UUID executionId = result.executionIds().isEmpty() ? null : result.executionIds().getFirst();
    String outcome;
    String message;
    if ("BLOCKED".equals(intent.status())) {
      outcome = "BLOCKED";
      message = intent.message();
    } else if (executionId == null) {
      outcome = "NO_MATCHING_FLOW";
      message = "No Developer Flow accepted the resolved task";
    } else if (result.needsDecision()) {
      outcome = "ADMITTED_NEEDS_DECISION";
      message = "Task admitted; a human decision is required before coding";
    } else {
      outcome = "ADMITTED";
      message = "Task admitted";
    }
    boolean existing = executionId != null && attachSnapshot(executionId, stored.content());
    log.info("Jira intake {} -> {} {} execution={} snapshot={}", key, outcome, intent.code(), executionId,
        snapshot.contentSha256());
    ResolvedIntent.RepositoryDecision repository = intent.repository();
    return new RunResult(key, outcome, intent.code(), message, executionId, existing,
        repository == null ? null : repository.canonicalSlug(),
        repository == null ? List.of() : repository.candidates(),
        repository == null ? null : repository.requestedRef(),
        repository == null ? null : repository.exactRevision(),
        intent.verificationPlan() == null ? null : intent.verificationPlan().id(),
        snapshot.contentSha256(), stored.path().toString(), intent.semanticTaskHash());
  }

  /** Attaches the snapshot once per execution; returns true when it was already attached (a replay). */
  private boolean attachSnapshot(UUID executionId, String content) {
    Optional<Artifact> current = artifacts.getLatest(executionId, SNAPSHOT_ARTIFACT);
    if (current.isPresent()) {
      return true;
    }
    artifacts.put(executionId, SNAPSHOT_ARTIFACT, content, "application/json", "jira-intake");
    return false;
  }
}
