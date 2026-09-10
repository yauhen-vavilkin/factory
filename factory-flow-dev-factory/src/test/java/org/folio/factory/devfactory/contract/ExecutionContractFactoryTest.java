package org.folio.factory.devfactory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.folio.factory.devfactory.profile.ExecutionProfile;
import org.folio.factory.devfactory.profile.ProfileEvidence;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.profile.VerificationPlan;
import org.folio.factory.sandbox.harness.TaskContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExecutionContractFactoryTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final TrustedProfileCatalog catalog = new TrustedProfileCatalog();
  private final ExecutionContractFactory factory = new ExecutionContractFactory();

  @Test
  void freezesOnlyWithActualPreparationReferencesAndProducesStableHash() {
    ResolvedIntent intent = intent();
    ExecutionContract.PreparationReferences preparation = new ExecutionContract.PreparationReferences(
        hash('s'), "sha256:" + hash('i'), "linux/arm64", hash('d'), hash('b'));
    ExecutionContract.ConfigurationReferences configuration = new ExecutionContract.ConfigurationReferences(
        hash('f').substring(0, 40), hash('w'), hash('h'), null,
        intent.profile().configurationHash(), intent.verificationPlan().configurationHash());

    ExecutionContract first = factory.freeze(intent, preparation, configuration);
    ExecutionContract second = factory.freeze(intent, preparation, configuration);

    assertThat(first.contractHash()).isEqualTo(second.contractHash()).matches("[0-9a-f]{64}");
    assertThat(first.source().exactRevision()).isEqualTo(intent.repository().exactRevision());
    assertThat(first.source().sourceSnapshotHash()).isEqualTo(preparation.sourceSnapshotHash());
    assertThat(first.task().rawTaskText()).isEqualTo("original task text");
    assertThat(first.unknowns()).isEmpty();
  }

  @Test
  void refusesToFabricateMissingImageSeedBaselineOrSourceHashes() {
    ExecutionContract.ConfigurationReferences configuration = new ExecutionContract.ConfigurationReferences(
        hash('f').substring(0, 40), hash('e'), hash('d'), null, hash('c'), hash('b'));
    assertThatThrownBy(() -> factory.freeze(intent(),
        new ExecutionContract.PreparationReferences(null, null, null, null, null), configuration))
        .hasMessageContaining("actual source, image, platform, dependency-seed and baseline");
  }

  @Test
  void catalogContainsPiFreshResolutionProfileWithGatewayOnlyNetwork() {
    ExecutionProfile profile = catalog.profile(TrustedProfileCatalog.JAVA_MAVEN_PI).orElseThrow();
    assertThat(profile.networkPolicy().execution()).isEqualTo("GATEWAY_ONLY");
    assertThat(profile.platform()).isEqualTo("linux/arm64");
    assertThat(profile.buildCommands()).contains(List.of("mvn", "-B", "-ntp", "test"));
  }

  @Test
  void operatorBudgetsCanOnlyTightenTrustedMaximums() {
    ResolvedIntent intent = intent();
    var budgets = ((tools.jackson.databind.node.ObjectNode) intent.task().constraints())
        .putObject("budgets");
    budgets.put("taskSeconds", 99999);
    budgets.put("maxModelCalls", 3);
    ExecutionContract contract = factory.freeze(intent,
        new ExecutionContract.PreparationReferences(hash('s'), "sha256:" + hash('i'),
            "linux/arm64", hash('d'), hash('b')),
        new ExecutionContract.ConfigurationReferences(hash('f').substring(0, 40), hash('e'),
            hash('d'), null, hash('c'), hash('b')));
    assertThat(contract.budgets().taskSeconds()).isEqualTo(intent.profile().budgets().taskSeconds());
    assertThat(contract.budgets().maxModelCalls()).isEqualTo(3);
  }

  @Test
  void harnessViewKeepsRawTextAndUsesOnlyTrustedPlanCommands() {
    ExecutionContract contract = factory.freeze(intent(),
        new ExecutionContract.PreparationReferences(hash('s'), "sha256:" + hash('i'),
            "linux/arm64", hash('d'), hash('b')),
        new ExecutionContract.ConfigurationReferences(hash('f').substring(0, 40), hash('e'),
            hash('d'), null, hash('c'), hash('b')));
    TaskContract view = HarnessTaskContractView.from(contract);
    assertThat(view.rawTaskText()).isEqualTo("original task text");
    assertThat(view.requiredChecks()).singleElement().satisfies(check -> {
      assertThat(check.id()).isEqualTo("maven-tests");
      assertThat(check.command()).isEqualTo("./mvnw -B -ntp test");
    });
    assertThat(view.directive()).contains("Original task text", "original task text");
  }

  private ResolvedIntent intent() {
    ExecutionProfile profile = catalog.profile(TrustedProfileCatalog.JAVA_MAVEN_21).orElseThrow();
    VerificationPlan plan = catalog.verificationPlan(TrustedProfileCatalog.JAVA_MAVEN_VERIFY).orElseThrow();
    TaskRequest task = new TaskRequest(1,
        new TaskRequest.SourceIdentity("JIRA", "MODSIDECAR-208", "MODSIDECAR", null),
        "folio-org/folio-module-sidecar", hash('r').substring(0, 40), null, profile.id(), plan.id(),
        "default", "LOCAL_ONLY", json.createObjectNode(), "Implement it",
        List.of(new TaskRequest.AcceptanceCriterion("AC-1", "It works", "TICKET")),
        json.createObjectNode(), "notes", "original task text", false);
    return new ResolvedIntent("ResolvedIntent/v1", "RESOLVED", null, "resolved", task,
        hash('t'), "file.inbox:" + hash('a'),
        new ResolvedIntent.RepositoryDecision("RESOLVED", "folio-org/folio-module-sidecar",
            "https://github.com/folio-org/folio-module-sidecar.git", null,
            hash('r').substring(0, 40), List.of("folio-org/folio-module-sidecar"), List.of()),
        new ProfileEvidence("JAVA", "MAVEN", "21", "Quarkus", "3.37.3", "3.9.16", "NONE",
            List.of("pom.xml"), List.of()), profile, plan,
        List.of("IMAGE_DIGEST_PENDING_PREPARATION",
            "DEPENDENCY_SEED_PENDING_PREPARATION", "BASELINE_PENDING_PREPARATION"),
        List.of(), false, hash('n'));
  }

  private static String hash(char value) {
    char hex = "0123456789abcdef".charAt(value % 16);
    return String.valueOf(hex).repeat(64);
  }
}
