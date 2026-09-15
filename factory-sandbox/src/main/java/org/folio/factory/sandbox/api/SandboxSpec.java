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
 *        the same task can never delete each other's workspaces. Owners are
 *        expected to key per attempt as well (T25 S09), so one attempt's
 *        create can never sweep another attempt's retained workspace.
 *        Callers without an execution identity may omit it and keep the
 *        legacy shared, task-named path (which is NOT an isolation boundary).
 * @param trustedDependencyCacheWriter true only for trusted preparation on the
 *        authoritative base revision: that sandbox may populate the shared
 *        trusted Maven repository. Every other sandbox (coding, repair,
 *        verification, export) sees the shared repository read-only and
 *        resolves new dependencies into a private repository of its own.
 */
public record SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch, String ownerId,
                          String image, String platform, String networkPolicy,
                          boolean trustedDependencyCacheWriter) {

  public SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch) {
    this(taskId, repoUrl, baseBranch, branch, null, null, null, null);
  }

  public SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch, String ownerId) {
    this(taskId, repoUrl, baseBranch, branch, ownerId, null, null, null);
  }

  public SandboxSpec(String taskId, String repoUrl, String baseBranch, String branch, String ownerId,
                     String image, String platform, String networkPolicy) {
    this(taskId, repoUrl, baseBranch, branch, ownerId, image, platform, networkPolicy, false);
  }
}
