package org.folio.factory.sandbox.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The task contract handed to the coding executor: the goal plus the
 * acceptance criteria, constraints and notes filed with the task. The payload
 * fields travel as JSON subtrees so nothing is truncated, re-parsed or
 * silently dropped on the way from the inbox to the model.
 *
 * <p>Constraints may include {@code allow_noop: true} (strict boolean): the
 * task explicitly permits finishing without any change to the repository —
 * for example when the requested behavior turns out to already work. Without
 * it, an unchanged result cannot be a validated success.</p>
 *
 * <p>Constraints may also carry {@code checks}: an array of explicit
 * verification obligations ({@link RequiredCheck}). Each check is a shell
 * command that discriminates the task's requirements; the coding harness
 * requires fresh, passing evidence for every declared check before a
 * terminal success outcome (T24).</p>
 */
public record TaskContract(String goal, JsonNode acceptance, JsonNode constraints, String notes) {

  /** Constraint key that explicitly permits a no-op (unchanged) result. */
  public static final String ALLOW_NOOP_KEY = "allow_noop";

  /** Constraint key carrying the explicit verification obligations. */
  public static final String CHECKS_KEY = "checks";

  /**
   * One explicit verification obligation derived from the frozen contract:
   * the discriminating {@code command} plus a stable {@code id} (defaults to
   * the command) used in reports and diagnostics.
   */
  public record RequiredCheck(String id, String command) {
  }

  private static final String ACCEPTANCE_HEADING = "Acceptance criteria (all must hold):";
  private static final String CONSTRAINTS_HEADING = "Constraints:";
  private static final String NOTES_HEADING = "Notes:";

  public TaskContract {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("task contract goal must not be blank");
    }
    if (acceptance != null && !acceptance.isArray()) {
      throw new IllegalArgumentException("task contract acceptance must be a JSON array");
    }
    if (constraints != null && !constraints.isObject()) {
      throw new IllegalArgumentException("task contract constraints must be a JSON object");
    }
    if (constraints != null && constraints.has(CHECKS_KEY)) {
      validateChecks(constraints.get(CHECKS_KEY));
    }
  }

  private static void validateChecks(JsonNode checks) {
    if (!checks.isArray()) {
      throw new IllegalArgumentException(
          "task contract '" + CHECKS_KEY + "' must be a JSON array of check objects");
    }
    for (JsonNode entry : checks) {
      if (entry == null || !entry.isObject()) {
        throw new IllegalArgumentException(
            "task contract '" + CHECKS_KEY + "' entry must be a JSON object");
      }
      JsonNode command = entry.get("command");
      if (command == null || !command.isString() || command.textValue().isBlank()) {
        throw new IllegalArgumentException(
            "task contract '" + CHECKS_KEY + "' entry needs a non-blank string 'command'");
      }
      JsonNode id = entry.get("id");
      if (id != null && !id.isNull() && (!id.isString() || id.textValue().isBlank())) {
        throw new IllegalArgumentException(
            "task contract '" + CHECKS_KEY + "' entry 'id' must be a non-blank string");
      }
    }
  }

  /**
   * The explicit verification obligations declared under
   * {@code constraints.checks}, in declaration order. Empty when the contract
   * declares none.
   */
  public List<RequiredCheck> requiredChecks() {
    if (constraints == null || !constraints.has(CHECKS_KEY)) {
      return List.of();
    }
    List<RequiredCheck> parsed = new ArrayList<>();
    for (JsonNode entry : constraints.get(CHECKS_KEY)) {
      String command = entry.get("command").textValue();
      JsonNode id = entry.get("id");
      parsed.add(new RequiredCheck(
          id == null || id.isNull() ? command : id.textValue(), command));
    }
    return List.copyOf(parsed);
  }

  /** A contract that carries only a goal (acceptance/constraints/notes empty). */
  public static TaskContract ofGoal(String goal) {
    return new TaskContract(goal, null, null, null);
  }

  /** {@code true} only when constraints carry {@code allow_noop: true}. */
  public boolean allowsNoOp() {
    JsonNode value = constraints == null ? null : constraints.get(ALLOW_NOOP_KEY);
    return value != null && value.isBoolean() && value.asBoolean();
  }

  /**
   * The first-turn user message for the model: the full contract rendered
   * verbatim. Empty sections are omitted; non-empty sections are never
   * truncated.
   */
  public String directive() {
    StringBuilder directive = new StringBuilder(goal);
    appendAcceptance(directive);
    appendConstraints(directive);
    appendNotes(directive);
    return directive.toString();
  }

  private void appendAcceptance(StringBuilder directive) {
    if (acceptance == null || acceptance.isEmpty()) {
      return;
    }
    directive.append("\n\n").append(ACCEPTANCE_HEADING);
    for (JsonNode criterion : acceptance) {
      directive.append("\n- ").append(criterion.asString());
    }
  }

  private void appendConstraints(StringBuilder directive) {
    if (constraints == null || constraints.isEmpty()) {
      return;
    }
    directive.append("\n\n").append(CONSTRAINTS_HEADING);
    for (Map.Entry<String, JsonNode> entry : constraints.properties()) {
      directive.append("\n- ").append(entry.getKey()).append(": ")
          .append(entry.getValue().toString());
    }
  }

  private void appendNotes(StringBuilder directive) {
    if (notes == null || notes.isBlank()) {
      return;
    }
    directive.append("\n\n").append(NOTES_HEADING).append("\n").append(notes);
  }
}
