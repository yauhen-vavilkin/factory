package org.folio.factory.devfactory.decision;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Human-answer artifacts for a coding runtime's normalized NEEDS_DECISION result. */
public final class CodingDecisionArtifacts {
    public static final String REQUEST = "dev_coding_decision_request.md";
    public static final String ANSWER = "dev_coding_decision_answer.md";
    public static final String UNANSWERED = "UNANSWERED";
    private static final Set<String> ANSWER_KEYS = Set.of("request_id", "answer");

    private CodingDecisionArtifacts() { }

    public record Request(boolean required, String requestId, String kind, String question,
                          List<String> options, String evidence) {
        public static Request none() { return new Request(false, hash("none"), "", "", List.of(), ""); }
        public static Request from(CodingOutcome.Decision decision) {
            String identity = decision.kind() + "\n" + decision.question() + "\n"
                    + String.join("\n", decision.options()) + "\n" + decision.evidence();
            return new Request(true, hash(identity), decision.kind().name(), decision.question(),
                    decision.options(), decision.evidence());
        }
    }

    public record Answer(String requestId, String answer) { }

    public static String renderRequest(FrontmatterCodec codec, Request request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("required", request.required());
        metadata.put("request_id", request.requestId());
        metadata.put("kind", request.kind());
        metadata.put("question", request.question());
        metadata.put("options", request.options());
        metadata.put("evidence", request.evidence());
        StringBuilder body = new StringBuilder("# Coding decision request\n\n");
        if (!request.required()) return codec.render(metadata, body.append("No decision is required.\n").toString());
        body.append(request.question()).append("\n\n");
        request.options().forEach(option -> body.append("- ").append(option).append('\n'));
        if (!request.evidence().isBlank()) body.append("\nEvidence: ").append(request.evidence()).append('\n');
        return codec.render(metadata, body.toString());
    }

    public static String renderAnswer(FrontmatterCodec codec, Answer answer) {
        return codec.render(Map.of("request_id", answer.requestId(), "answer", answer.answer()), """
                # Coding decision answer

                AMEND this artifact and replace `UNANSWERED` with the confirmed human answer.
                APPROVE without amending does not authorize another coding attempt.
                """);
    }

    public static Request parseRequest(FrontmatterCodec codec, String content) {
        JsonNode metadata = codec.parse(content).metadata();
        List<String> options = new java.util.ArrayList<>();
        metadata.path("options").forEach(option -> options.add(option.asString("")));
        return new Request(metadata.path("required").asBoolean(false), metadata.path("request_id").asString(""),
                metadata.path("kind").asString(""), metadata.path("question").asString(""), options,
                metadata.path("evidence").asString(""));
    }

    public static Answer parseAnswer(FrontmatterCodec codec, String content) {
        JsonNode metadata = codec.parse(content).metadata();
        Set<String> keys = new TreeSet<>(metadata.propertyNames());
        if (!keys.equals(ANSWER_KEYS))
            throw new IllegalArgumentException("Coding decision answer must contain exactly "
                    + new TreeSet<>(ANSWER_KEYS) + " but has " + keys);
        String requestId = metadata.path("request_id").asString("");
        String answer = metadata.path("answer").asString("").strip();
        if (!requestId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Coding decision answer has an invalid request_id");
        if (answer.isBlank() || answer.length() > 4000)
            throw new IllegalArgumentException("Coding decision answer must contain 1-4000 characters");
        return new Answer(requestId, answer);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
