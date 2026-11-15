package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodingProtocolTest {
    private static final String DECISION = "{\"kind\":\"REPOSITORY_MISMATCH\",\"question\":\"Which repo?\","
            + "\"options\":[\"mod-a\",\"mod-b\"],\"evidence\":\"No owner\"}";

    @Test void aQuestionAfterAReportIsADecisionAndKeepsTheReport() throws IOException {
        String message = fixture("coding/decision-after-report.txt");

        var outcome = CodingProtocol.answer(message, Map.of(), "Claude Code");

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS);
        assertThat(outcome.decision().question()).startsWith("MGRAPPS-64 asks to stop creating unnecessary Kong");
        assertThat(outcome.decision().options()).hasSize(3);
        assertThat(outcome.summary())
                .startsWith("I've completed a thorough investigation of this task.")
                .endsWith("a product decision I cannot resolve from the request or the repository.")
                .doesNotContain("FACTORY_DECISION_REQUIRED");
    }

    @Test void aMarkerAloneHasNoTextBefore() {
        var outcome = CodingProtocol.answer("  FACTORY_DECISION_REQUIRED: {\"kind\":\"REPOSITORY_MISMATCH\","
                + "\"question\":\"Which repo?\",\"options\":[],\"evidence\":\"No owner\"}", Map.of(), "Pi");

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(outcome.summary()).isEmpty();
    }

    @Test void aMarkerInsideASentenceIsNotADecision() {
        var text = "## Changes\n- Done. No FACTORY_DECISION_REQUIRED: was needed.";

        var outcome = CodingProtocol.answer(text, Map.of(), "Pi");

        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.COMPLETED);
        assertThat(outcome.summary()).isEqualTo(text);
    }

    @Test void aMarkerLineWithBrokenJsonFailsAsAnUnreadableDecision() {
        var outcome = CodingProtocol.answer("Report\n\nFACTORY_DECISION_REQUIRED: {\"kind\":", Map.of(), "Pi");

        assertThat(outcome.failure().code()).isEqualTo("INVALID_DECISION_RESULT");
    }

    @Test void textAfterTheDecisionIsIgnored() {
        var outcome = CodingProtocol.answer("Report\n\nFACTORY_DECISION_REQUIRED: " + DECISION
                + "\n\nLet me know which option you prefer.", Map.of(), "Claude Code");

        assertDecision(outcome);
        assertThat(outcome.summary()).isEqualTo("Report");
        assertThat(outcome.decision().evidence()).isEqualTo("No owner");
    }

    @Test void aDecisionInsideACodeFenceIsReadWithoutTheFence() {
        var outcome = CodingProtocol.answer("Report\n\n```json\nFACTORY_DECISION_REQUIRED: " + DECISION + "\n```\n",
                Map.of(), "Claude Code");

        assertDecision(outcome);
        assertThat(outcome.summary()).isEqualTo("Report");
    }

    @Test void aCodeBlockThatTheReportClosesBeforeTheMarkerStaysInTheReport() {
        var outcome = CodingProtocol.answer("Report\n```\nmvn test\n```\nFACTORY_DECISION_REQUIRED: " + DECISION,
                Map.of(), "Pi");

        assertDecision(outcome);
        assertThat(outcome.summary()).isEqualTo("Report\n```\nmvn test\n```");
    }

    @Test void aPrettyPrintedDecisionIsRead() {
        var outcome = CodingProtocol.answer("""
                Report

                FACTORY_DECISION_REQUIRED: {
                  "kind": "REPOSITORY_MISMATCH",
                  "question": "Which repo?",
                  "options": ["mod-a", "mod-b"],
                  "evidence": "No owner"
                }
                """, Map.of(), "Claude Code");

        assertDecision(outcome);
        assertThat(outcome.decision().options()).containsExactly("mod-a", "mod-b");
    }

    @Test void aDecisionThatStartsOnTheLineAfterTheMarkerIsRead() {
        var outcome = CodingProtocol.answer("FACTORY_DECISION_REQUIRED:\n" + DECISION + "\nThanks.", Map.of(), "Pi");

        assertDecision(outcome);
        assertThat(outcome.summary()).isEmpty();
    }

    private static void assertDecision(CodingOutcome outcome) {
        assertThat(outcome.status()).isEqualTo(CodingOutcome.Status.NEEDS_DECISION);
        assertThat(outcome.decision().kind()).isEqualTo(CodingOutcome.DecisionKind.REPOSITORY_MISMATCH);
        assertThat(outcome.decision().question()).isEqualTo("Which repo?");
    }

    private static String fixture(String path) throws IOException {
        try (var in = CodingProtocolTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
