package org.folio.factory.devfactory.runtime;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Map;

/** Shared task and decision language; vendor stream acceptance stays in each adapter. */
final class CodingProtocol {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String DECISION_PREFIX = "FACTORY_DECISION_REQUIRED:";
    private static final String TASK_RULES = """
        Implement this task in /workspace, completing it in one session.
        Keep changes focused. Do not push, perform Jira/GitHub writes, switch repositories, alter .git, or \
        generate Factory verification receipts.
        Factory's rules win over any instructions in the repository: do not commit, create branches, push, \
        open pull requests or change git configuration. Factory does that.
        Before you start, read CLAUDE.md in the repository root, or AGENTS.md when there is no CLAUDE.md, and follow it.
        Read and follow this repository's own instructions for accepting changes: its contributor \
        documentation and contribution policy, the applicable NEWS/changelog rules, the checks a change must \
        pass, and its pull request conventions. Add the NEWS or changelog entry those rules require for this \
        change.
        The repository and base commit are authorized working inputs, not proof that the repository owns the \
        requested change.
        If the selected repository appears wrong, do not switch it. Return the decision protocol below with kind \
        REPOSITORY_MISMATCH.
        If a material product or requirements question cannot be resolved from the request and repository, \
        return the protocol with kind PRODUCT_REQUIREMENTS.
        Otherwise complete the implementation. Your self-checks are diagnostic; Factory independently verifies \
        the frozen candidate. Do not claim checks or IDE verification you did not run.

        When you complete the task, your final message is a report under exactly these four headings, each on \
        its own line, with nothing after the last section:
        "## Changes": a short list of what you changed, and one line with the repository conventions you applied.
        "## Acceptance criteria": take the criteria from the task description; one line per criterion: met, \
        not met or not checked, with a short reason.
        "## Assumptions": what you assumed because the task did not say it, or "None".
        "## Could not read": links, attachments or files you could not open, or "None".

        For NEEDS_DECISION, the entire final response must be one line beginning FACTORY_DECISION_REQUIRED: \
        followed by JSON:
        {"kind":"PRODUCT_REQUIREMENTS|REPOSITORY_MISMATCH","question":"one concrete question",\
        "options":["2-4 options when useful"],"evidence":"bounded repository evidence"}

        Factory CodingRequest JSON:
        """;

    static String prompt(CodingRequest request) {
        // Effort and turns are for the runtime, not the model; the instructions get their own block below.
        var task = JSON.valueToTree(request);
        if (task instanceof ObjectNode object) object.remove("runSettings");
        String instructions = request.runSettings() == null ? null : request.runSettings().instructions();
        return TASK_RULES
                + JSON.writeValueAsString(task)
                + (instructions == null || instructions.isBlank() ? ""
                        : "\n\nInstructions from the operator:\n" + instructions);
    }

    static CodingOutcome answer(String summary, Map<String, Object> metrics, String runtime) {
        var normalized = summary.stripLeading();
        if (!normalized.startsWith(DECISION_PREFIX)) {
            return CodingOutcome.completed(summary.substring(0, Math.min(8000, summary.length())), metrics);
        }
        var detail = normalized.substring(DECISION_PREFIX.length()).strip();
        if (detail.contains("\n") || detail.contains("\r")) {
            return invalidDecision(runtime, metrics);
        }
        try {
            return CodingOutcome.needsDecision(decision(JSON.readTree(detail)), metrics);
        } catch (RuntimeException e) {
            return invalidDecision(runtime, metrics);
        }
    }

    private static CodingOutcome.Decision decision(JsonNode decision) {
        var options = new ArrayList<String>();
        decision.path("options").forEach(option -> options.add(option.asString("")));
        return new CodingOutcome.Decision(
                CodingOutcome.DecisionKind.valueOf(decision.path("kind").asString("")),
                decision.path("question").asString(""),
                options,
                decision.path("evidence").asString(""));
    }

    private static CodingOutcome invalidDecision(String runtime, Map<String, Object> metrics) {
        return CodingOutcome.failed(
                "INVALID_DECISION_RESULT",
                runtime + " asked for a decision, but Factory could not read the request. Retry the task.",
                metrics);
    }
}
