package org.folio.factory.sandbox.api;

/**
 * Request to create a sandbox for one task.
 *
 * @param taskId operator-facing task identity, also the default workspace key
 * @param repoUrl repository to clone
 * @param baseBranch base branch to start from
 * @param branch task branch to create
 * @param ownerId identity of the execution that owns this sandbox (T22 R4).
 *        When present, the local-mode workspace directory is keyed by it — a
 *        collision-free execution identity — so two concurrent executions of
 *        the same task can never delete each other's workspaces. Callers
 *        without an execution identity may omit it and keep the legacy
 *        shared, task-named path (which is NOT an isolation boundary).
 */
public record SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch, String ownerId) {

  public SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch) {
    this(taskId, repoUrl, baseBranch, branch, null);
  }
}
