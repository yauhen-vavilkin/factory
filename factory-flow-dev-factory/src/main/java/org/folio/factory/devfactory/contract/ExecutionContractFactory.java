package org.folio.factory.devfactory.contract;

import java.util.List;
import java.util.Map;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Freezes a resolved intent only after preparation produced real immutable references. */
public final class ExecutionContractFactory {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private final JsonMapper json = JsonMapper.builder().build();

  public ExecutionContract freeze(ResolvedIntent intent,
                                  ExecutionContract.PreparationReferences preparation,
                                  ExecutionContract.ConfigurationReferences configuration) {
    if (intent == null || !"RESOLVED".equals(intent.status())) {
      throw new IllegalArgumentException("only a resolved intent can be frozen");
    }
    requirePreparation(preparation);
    requireConfiguration(configuration);
    ExecutionProfile.Budgets budgets = intent.profile().budgets().tighten(requestedBudgets(intent));
    List<ExecutionContract.Acceptance> acceptance = intent.task().acceptanceCriteria().stream()
        .map(item -> new ExecutionContract.Acceptance(item.id(), item.text(), item.source())).toList();
    List<String> remainingUnknowns = intent.unknowns().stream()
        .filter(value -> !value.endsWith("_PENDING_PREPARATION")).toList();
    if (!remainingUnknowns.isEmpty()) {
      throw new IllegalArgumentException("material unknowns must be resolved before freezing: "
          + remainingUnknowns);
    }
    ExecutionContract draft = new ExecutionContract("ExecutionContract/v1",
        new ExecutionContract.TaskIdentity(intent.task().source().type(), intent.task().source().id(),
            intent.task().runKey(), intent.task().deliveryMode(), intent.semanticTaskHash(),
            intent.task().rawTaskText(), intent.task().goal(), intent.task().notes()),
        new ExecutionContract.SourceSnapshot(intent.repository().canonicalSlug(),
            intent.repository().origin(), intent.repository().requestedRef(),
            intent.repository().exactRevision(), preparation.sourceSnapshotHash()),
        acceptance, remainingUnknowns, json.convertValue(intent.task().constraints(), MAP),
        intent.profile(), intent.verificationPlan(), preparation,
        new ExecutionContract.Policies(intent.profile().networkPolicy().execution(),
            "PRIVATE_SANDBOX_WORKSPACE", "NO_TASK_OR_MODEL_SECRETS", "TRUSTED_ARGV_ONLY"),
        budgets, configuration, null);
    ObjectNode node = json.valueToTree(draft);
    node.remove("contractHash");
    String hash = CanonicalJson.sha256(node);
    return new ExecutionContract(draft.schema(), draft.task(), draft.source(), draft.acceptance(),
        draft.unknowns(), draft.constraints(), draft.executionProfile(), draft.verificationPlan(),
        draft.preparation(), draft.policies(), draft.budgets(), draft.configuration(), hash);
  }

  private ExecutionProfile.Budgets requestedBudgets(ResolvedIntent intent) {
    var node = intent.task().constraints().path("budgets");
    if (!node.isObject()) {
      return null;
    }
    ExecutionProfile.Budgets maximum = intent.profile().budgets();
    return new ExecutionProfile.Budgets(positiveOr(node, "taskSeconds", maximum.taskSeconds()),
        positiveOr(node, "commandSeconds", maximum.commandSeconds()),
        positiveOr(node, "buildSeconds", maximum.buildSeconds()),
        (int) positiveOr(node, "maxModelCalls", maximum.maxModelCalls()),
        positiveOr(node, "maxOutputTokens", maximum.maxOutputTokens()));
  }

  private static long positiveOr(tools.jackson.databind.JsonNode node, String field, long fallback) {
    var value = node.get(field);
    if (value == null) {
      return fallback;
    }
    if (!value.isIntegralNumber() || value.asLong() <= 0) {
      throw new IllegalArgumentException("budget '" + field + "' must be a positive integer");
    }
    return value.asLong();
  }

  private static void requirePreparation(ExecutionContract.PreparationReferences value) {
    if (value == null || blank(value.sourceSnapshotHash()) || blank(value.imageDigest()) || blank(value.platform())
        || blank(value.dependencySeedHash()) || blank(value.baselineHash())) {
      throw new IllegalArgumentException(
          "freezing requires actual source, image, platform, dependency-seed and baseline references");
    }
    requireSha256("sourceSnapshotHash", value.sourceSnapshotHash());
    if (!value.imageDigest().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("imageDigest must be an immutable sha256 digest");
    }
    requireSha256("dependencySeedHash", value.dependencySeedHash());
    requireSha256("baselineHash", value.baselineHash());
  }

  private static void requireConfiguration(ExecutionContract.ConfigurationReferences value) {
    if (value == null || blank(value.factoryRevision()) || blank(value.flowDescriptorHash())
        || blank(value.harnessHash()) || blank(value.profileCatalogHash())
        || blank(value.verificationCatalogHash())) {
      throw new IllegalArgumentException("freezing requires immutable Factory/flow/harness/catalog references");
    }
    if (!configurationCommit(value.factoryRevision())) {
      throw new IllegalArgumentException("factoryRevision must be a full 40-character commit SHA");
    }
    requireSha256("flowDescriptorHash", value.flowDescriptorHash());
    requireSha256("harnessHash", value.harnessHash());
    if (!blank(value.modelProfileHash())) {
      requireSha256("modelProfileHash", value.modelProfileHash());
    }
    requireSha256("profileCatalogHash", value.profileCatalogHash());
    requireSha256("verificationCatalogHash", value.verificationCatalogHash());
  }

  private static void requireSha256(String name, String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a canonical SHA-256 hex value");
    }
  }

  private static boolean configurationCommit(String value) {
    return value != null && value.matches("[0-9a-f]{40}");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
