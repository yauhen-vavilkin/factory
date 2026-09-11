package org.folio.factory.devfactory.profile;

import java.util.List;
import java.util.Map;

/** Immutable snapshot selected from the trusted Factory catalog. */
public record ExecutionProfile(
    String id,
    String catalogVersion,
    String language,
    String buildTool,
    String languageVersion,
    String framework,
    String imageReference,
    String imageDigest,
    String platform,
    String modelProvider,
    String modelId,
    String workdir,
    List<List<String>> buildCommands,
    Map<String, String> reportGlobs,
    NetworkPolicy networkPolicy,
    ResourcePolicy resources,
    Budgets budgets,
    String configurationHash) {

  public record NetworkPolicy(String preparation, String execution) {
  }

  public record ResourcePolicy(double cpus, long memoryMiB, int pids, long diskMiB) {
  }

  public record Budgets(long taskSeconds, long commandSeconds, long buildSeconds,
                        int maxModelCalls, long maxOutputTokens) {
    public Budgets tighten(Budgets requested) {
      if (requested == null) {
        return this;
      }
      return new Budgets(Math.min(taskSeconds, requested.taskSeconds),
          Math.min(commandSeconds, requested.commandSeconds),
          Math.min(buildSeconds, requested.buildSeconds),
          Math.min(maxModelCalls, requested.maxModelCalls),
          Math.min(maxOutputTokens, requested.maxOutputTokens));
    }
  }
}
