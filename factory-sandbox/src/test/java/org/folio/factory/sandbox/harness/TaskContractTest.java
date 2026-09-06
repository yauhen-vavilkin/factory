package org.folio.factory.sandbox.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class TaskContractTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void goalOnlyDirectiveCarriesGoalWithoutEmptySections() {
    TaskContract contract = new TaskContract("fix the NPE", null, null, null);

    String directive = contract.directive();

    assertThat(directive).contains("fix the NPE");
    assertThat(directive).doesNotContain("Acceptance criteria");
    assertThat(directive).doesNotContain("Constraints");
    assertThat(directive).doesNotContain("Notes");
  }

  @Test
  void directiveCarriesEveryAcceptanceCriterionVerbatim() {
    String longCriterion = "rg -n 'SearchHelper' returns three hits and the "
        + "reproduction command fails with the documented NPE before the change "
        + "and passes afterwards, including the null-argument edge case".repeat(3);
    ArrayNode acceptance = mapper.createArrayNode();
    acceptance.add("patch.diff modifies README.md");
    acceptance.add(longCriterion);
    TaskContract contract = new TaskContract("fix the NPE", acceptance, null, null);

    String directive = contract.directive();

    assertThat(directive).contains("fix the NPE");
    assertThat(directive).contains("patch.diff modifies README.md");
    assertThat(directive).contains(longCriterion);
    assertThat(directive).contains("Acceptance criteria");
  }

  @Test
  void directiveCarriesEveryConstraintVerbatim() {
    ObjectNode constraints = mapper.createObjectNode();
    constraints.put("language", "java-21");
    constraints.set("allow_paths", mapper.createArrayNode()
        .add("README.md").add("docs/"));
    TaskContract contract = new TaskContract("fix the NPE", null, constraints, null);

    String directive = contract.directive();

    assertThat(directive).contains("Constraints");
    assertThat(directive).contains("language");
    assertThat(directive).contains("java-21");
    assertThat(directive).contains("allow_paths");
    assertThat(directive).contains("README.md");
    assertThat(directive).contains("docs/");
  }

  @Test
  void directiveCarriesNotesVerbatim() {
    TaskContract contract = new TaskContract("fix the NPE", null, null,
        "The regression appeared after release 4.2; see ticket FOLIO-1234.");

    String directive = contract.directive();

    assertThat(directive).contains("Notes");
    assertThat(directive)
        .contains("The regression appeared after release 4.2; see ticket FOLIO-1234.");
  }

  @Test
  void emptyAcceptanceAndConstraintsOmitSectionsButKeepNotes() {
    TaskContract contract = new TaskContract("fix the NPE",
        mapper.createArrayNode(), mapper.createObjectNode(), "context note");

    String directive = contract.directive();

    assertThat(directive).doesNotContain("Acceptance criteria");
    assertThat(directive).doesNotContain("Constraints");
    assertThat(directive).contains("context note");
  }

  @Test
  void allowNoopTrueOnlyForBooleanTrueConstraint() {
    assertThat(contractWithConstraint("allow_noop", true).allowsNoOp()).isTrue();
    assertThat(contractWithConstraint("allow_noop", false).allowsNoOp()).isFalse();
    assertThat(new TaskContract("goal", null, mapper.createObjectNode(), null).allowsNoOp())
        .isFalse();
    assertThat(new TaskContract("goal", null, null, null).allowsNoOp()).isFalse();
    ObjectNode textual = mapper.createObjectNode();
    textual.put("allow_noop", "true");
    assertThat(new TaskContract("goal", null, textual, null).allowsNoOp()).isFalse();
  }

  @Test
  void blankGoalRejected() {
    assertThatThrownBy(() -> new TaskContract("  ", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TaskContract(null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonArrayAcceptanceRejected() {
    JsonNode scalar = mapper.readTree("\"not a list\"");

    assertThatThrownBy(() -> new TaskContract("goal", scalar, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonObjectConstraintsRejected() {
    JsonNode scalar = mapper.readTree("42");

    assertThatThrownBy(() -> new TaskContract("goal", null, scalar, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ofGoalCarriesBareGoal() {
    TaskContract contract = TaskContract.ofGoal("just the goal");

    assertThat(contract.goal()).isEqualTo("just the goal");
    assertThat(contract.acceptance()).isNull();
    assertThat(contract.constraints()).isNull();
    assertThat(contract.notes()).isNull();
    assertThat(contract.allowsNoOp()).isFalse();
    assertThat(contract.directive()).isEqualTo("just the goal");
  }

  private TaskContract contractWithConstraint(String key, boolean value) {
    ObjectNode constraints = mapper.createObjectNode();
    constraints.put(key, value);
    return new TaskContract("goal", null, constraints, null);
  }
}
