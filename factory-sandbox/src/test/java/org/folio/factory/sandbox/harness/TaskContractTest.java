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

  /**
   * T24 R1: the frozen contract carries explicit, machine-readable
   * verification obligations under {@code constraints.checks}; each entry
   * needs a non-blank {@code command}, {@code id} defaults to the command.
   */
  @Test
  void requiredChecksParseIdAndCommandWithCommandAsDefaultId() {
    ArrayNode checks = mapper.createArrayNode();
    checks.addObject().put("id", "tests").put("command", "cd repo && mvn test -B");
    checks.addObject().put("command", "rg -n SearchHelper repo/src");
    ObjectNode constraints = mapper.createObjectNode();
    constraints.set("checks", checks);

    TaskContract contract = new TaskContract("goal", null, constraints, null);

    assertThat(contract.requiredChecks()).containsExactly(
        new TaskContract.RequiredCheck("tests", "cd repo && mvn test -B"),
        new TaskContract.RequiredCheck("rg -n SearchHelper repo/src",
            "rg -n SearchHelper repo/src"));
  }

  /** T24 R1: no {@code checks} key means no verification obligations. */
  @Test
  void requiredChecksEmptyWithoutChecksConstraint() {
    assertThat(new TaskContract("goal", null, null, null).requiredChecks()).isEmpty();
    assertThat(new TaskContract("goal", null, mapper.createObjectNode(), null).requiredChecks())
        .isEmpty();
    ObjectNode other = mapper.createObjectNode();
    other.put("allow_noop", true);
    assertThat(new TaskContract("goal", null, other, null).requiredChecks()).isEmpty();
  }

  /** T24 R1: a malformed checks constraint fails fast at contract construction. */
  @Test
  void malformedChecksConstraintRejected() {
    JsonNode notArray = mapper.readTree("{\"checks\":{\"command\":\"mvn test\"}}");
    assertThatThrownBy(() -> new TaskContract("goal", null, notArray, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checks");

    ArrayNode entryNotObject = mapper.createArrayNode();
    entryNotObject.add("mvn test");
    assertThatThrownBy(() -> contractWithChecks(entryNotObject))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'checks' entry");

    ArrayNode missingCommand = mapper.createArrayNode();
    missingCommand.addObject().put("id", "tests");
    assertThatThrownBy(() -> contractWithChecks(missingCommand))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("command");

    ArrayNode blankCommand = mapper.createArrayNode();
    blankCommand.addObject().put("command", "   ");
    assertThatThrownBy(() -> contractWithChecks(blankCommand))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("command");

    ArrayNode blankId = mapper.createArrayNode();
    blankId.addObject().put("id", "  ").put("command", "mvn test");
    assertThatThrownBy(() -> contractWithChecks(blankId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  private TaskContract contractWithChecks(JsonNode checks) {
    ObjectNode constraints = mapper.createObjectNode();
    constraints.set("checks", checks);
    return new TaskContract("goal", null, constraints, null);
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
