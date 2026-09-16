package org.folio.factory.devfactory.jira;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.folio.factory.devfactory.contract.TaskRequest;

/**
 * Deterministic check that the root Jira issue itself states an executable
 * task: a meaningful description or an explicit acceptance-criteria field.
 * The summary alone is not enough, and linked issues and comments never count:
 * they are context, not requirements of this issue. No model is involved.
 */
final class JiraTaskSufficiency {
  static final String CATEGORY = "TASK_REQUIREMENTS_MISSING";
  static final int MIN_DESCRIPTION_WORDS = 4;
  static final int MIN_DESCRIPTION_LETTERS = 20;
  static final int MIN_CRITERIA_WORDS = 2;
  static final int MIN_CRITERIA_LETTERS = 10;
  private static final Set<String> PLACEHOLDERS = Set.of("tbd", "tba", "todo", "to do", "n a", "na", "none",
      "empty", "wip", "placeholder", "see summary", "see title", "see above", "to be defined",
      "to be described", "to be added", "details to follow", "description", "no description");

  private JiraTaskSufficiency() {
  }

  /** The decision to ask when the root issue has no actionable requirements, or empty when it has. */
  static Optional<TaskRequest.DeclaredDecision> missingRequirements(JiraTaskSnapshot snapshot) {
    if (meaningful(snapshot.description(), snapshot.summary(), MIN_DESCRIPTION_WORDS, MIN_DESCRIPTION_LETTERS)
        || snapshot.acceptanceCriteriaFields().stream().anyMatch(field ->
            meaningful(field.value(), snapshot.summary(), MIN_CRITERIA_WORDS, MIN_CRITERIA_LETTERS))) {
      return Optional.empty();
    }
    return Optional.of(new TaskRequest.DeclaredDecision("jira-task-requirements", CATEGORY,
        "Jira issue " + snapshot.issueKey() + " states no actionable requirement: its description is empty or "
            + "a placeholder and it has no acceptance-criteria field. What must the change do?",
        "Factory does not let the coding runtime invent the task from a summary. Linked issues and comments "
            + "are context only and are not treated as this issue's requirements. Answer with the missing "
            + "requirement, or reject this task, complete the Jira issue and run it again.",
        List.of(), null, null, List.of()));
  }

  static boolean meaningful(String text, String summary, int minWords, int minLetters) {
    String normalized = normalize(text);
    if (normalized.isEmpty() || PLACEHOLDERS.contains(normalized) || normalized.equals(normalize(summary))) {
      return false;
    }
    long letters = normalized.chars().filter(Character::isLetterOrDigit).count();
    return normalized.split(" ").length >= minWords && letters >= minLetters;
  }

  /** Lower-case words only: Jira wiki markup, punctuation and whitespace do not count as content. */
  private static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String withoutMarkup = text
        .replaceAll("\\{(code|noformat|quote|panel|color)[^}]*}", " ")
        .replaceAll("(?m)^\\s*h[1-6]\\.\\s*", " ");
    return withoutMarkup.toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim();
  }
}
