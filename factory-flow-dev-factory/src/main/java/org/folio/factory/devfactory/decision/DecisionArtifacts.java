package org.folio.factory.devfactory.decision;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The decision request (trusted, written by intake) and its answer (the only
 * artifact a reviewer may amend). The answer is bound to its request by
 * {@code request_id}, a hash of the request's identity.
 */
public final class DecisionArtifacts {

    public static final String REQUEST = "dev_decision_request.md";
    public static final String ANSWER = "dev_decision_answer.md";

    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ANSWER_KEYS = Set.of("request_id", "choice");

    private DecisionArtifacts() {
    }

    public record Option(String id, String label) {
    }

    public record Request(boolean required, String requestId, String issueKey, String question,
                          List<Option> options, String recommended) {

        public static Request none(String issueKey) {
            return new Request(false, hashRequest(issueKey, "", List.of()), issueKey, "", List.of(), null);
        }

        public static Request of(String issueKey, String question, List<Option> options) {
            return new Request(true, hashRequest(issueKey, question, options), issueKey, question,
                    List.copyOf(options), options.getFirst().id());
        }

        public boolean offers(String choice) {
            return options.stream().anyMatch(o -> o.id().equals(choice));
        }
    }

    public record Answer(String requestId, String choice) {
    }

    public static String renderRequest(FrontmatterCodec codec, Request request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("required", request.required());
        metadata.put("request_id", request.requestId());
        metadata.put("issue_key", request.issueKey());
        metadata.put("question", request.question());
        metadata.put("options", request.options().stream()
                .map(o -> Map.of("id", o.id(), "label", o.label())).toList());
        metadata.put("recommended", request.recommended());
        StringBuilder body = new StringBuilder("# Decision request\n\n");
        if (!request.required()) {
            body.append("No decision is required.\n");
        } else {
            body.append(request.question()).append("\n\n");
            request.options().forEach(o -> body.append("- `").append(o.id()).append("` — ").append(o.label())
                    .append(o.id().equals(request.recommended()) ? " (recommended)" : "").append('\n'));
        }
        return codec.render(metadata, body.toString());
    }

    public static Request parseRequest(FrontmatterCodec codec, String content) {
        JsonNode m = codec.parse(content).metadata();
        List<Option> options = new ArrayList<>();
        m.path("options").forEach(o -> options.add(new Option(o.path("id").asString(""), o.path("label").asString(""))));
        return new Request(m.path("required").asBoolean(false), m.path("request_id").asString(""),
                m.path("issue_key").asString(""), m.path("question").asString(""), options,
                m.path("recommended").asString(null));
    }

    public static String renderAnswer(FrontmatterCodec codec, Answer answer) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("request_id", answer.requestId());
        metadata.put("choice", answer.choice());
        return codec.render(metadata, """
                # Decision answer

                APPROVE accepts the `choice` above (the recommended option).
                To choose differently, AMEND this artifact and change only `choice`
                to another option id listed in the decision request.
                """);
    }

    /**
     * Structural check of an answer; binding to the actual request is checked by
     * the consuming worker.
     *
     * @throws IllegalArgumentException when the answer is malformed
     */
    public static Answer parseAnswer(FrontmatterCodec codec, String content) {
        JsonNode m = codec.parse(content).metadata();
        Set<String> keys = new TreeSet<>(m.propertyNames());
        if (!keys.equals(ANSWER_KEYS)) {
            throw new IllegalArgumentException("Decision answer must contain exactly " + new TreeSet<>(ANSWER_KEYS)
                    + " but has " + keys);
        }
        String requestId = m.path("request_id").asString("");
        if (!REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("Decision answer has an invalid request_id");
        }
        String choice = m.path("choice").isString() ? m.path("choice").asString().strip() : "";
        if (choice.isEmpty()) {
            throw new IllegalArgumentException("Decision answer has no choice");
        }
        return new Answer(requestId, choice);
    }

    private static String hashRequest(String issueKey, String question, List<Option> options) {
        StringBuilder identity = new StringBuilder(issueKey).append('\n').append(question);
        options.forEach(o -> identity.append('\n').append(o.id()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
