package org.folio.factory.devfactory.runtime;

import java.util.List;
import java.util.Map;

/** Normalized result of a coding runtime; vendor protocols remain inside adapters. */
public record CodingOutcome(Status status, String summary, Decision decision, Failure failure,
                            Map<String, Object> metrics) {

    public CodingOutcome {
        if (status == null) throw new IllegalArgumentException("Coding outcome status is required");
        summary = summary == null ? "" : summary;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        if (status == Status.NEEDS_DECISION && decision == null)
            throw new IllegalArgumentException("NEEDS_DECISION requires a decision request");
        if (status == Status.FAILED && failure == null)
            throw new IllegalArgumentException("FAILED requires failure details");
        if (status != Status.NEEDS_DECISION && decision != null)
            throw new IllegalArgumentException(status + " cannot contain a decision request");
        if (status != Status.FAILED && failure != null)
            throw new IllegalArgumentException(status + " cannot contain failure details");
    }

    public static CodingOutcome completed(String summary, Map<String, Object> metrics) {
        return new CodingOutcome(Status.COMPLETED, summary, null, null, metrics);
    }

    public static CodingOutcome needsDecision(Decision decision, Map<String, Object> metrics) {
        return new CodingOutcome(Status.NEEDS_DECISION, "", decision, null, metrics);
    }

    public static CodingOutcome failed(String code, String message, Map<String, Object> metrics) {
        return new CodingOutcome(Status.FAILED, "", null, new Failure(code, message), metrics);
    }

    public enum Status { COMPLETED, NEEDS_DECISION, FAILED }

    public enum DecisionKind { PRODUCT_REQUIREMENTS, REPOSITORY_MISMATCH }

    public record Decision(DecisionKind kind, String question, List<String> options, String evidence) {
        public Decision {
            if (kind == null) throw new IllegalArgumentException("Decision kind is required");
            question = bounded(question, 1000);
            evidence = bounded(evidence, 1600);
            options = options == null ? List.of() : options.stream().map(option -> bounded(option, 400)).toList();
            if (question.isBlank()) throw new IllegalArgumentException("Decision question is required");
            if (!options.isEmpty() && (options.size() < 2 || options.size() > 4))
                throw new IllegalArgumentException("Decision must contain 2-4 options when options are supplied");
            if (options.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("Decision options cannot be blank");
        }
    }

    public record Failure(String code, String message) {
        public Failure {
            code = bounded(code, 100);
            message = bounded(message, 2000);
            if (code.isBlank() || message.isBlank())
                throw new IllegalArgumentException("Failure code and message are required");
        }
    }

    private static String bounded(String value, int limit) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
        return clean.substring(0, Math.min(limit, clean.length()));
    }
}
