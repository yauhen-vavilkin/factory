package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * T22 R1: the frozen admission-identity definition. The admitted revision of
 * an inbox task is its normalized content (the eight parsed fields with
 * defaults applied) — not the file name, not the raw bytes. These tests ARE
 * the definition: changing any of them changes the admission contract.
 */
class FileInboxAdmissionKeyTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void identicalContentYieldsIdenticalKey() {
        assertThat(FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal one")))
                .isEqualTo(FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal one")));
    }

    @Test
    void revisedContentIsADifferentAdmission() {
        assertThat(FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal one")))
                .isNotEqualTo(FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal two")));
    }

    @Test
    void differentTaskIdIsADifferentAdmission() {
        assertThat(FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal one")))
                .isNotEqualTo(FileInboxAdmissionKey.of(taskPayload("TASK-2", "goal one")));
    }

    @Test
    void everyNormalizedFieldParticipatesInTheIdentity() {
        JsonNode base = taskPayload("TASK-1", "goal one");
        assertThat(FileInboxAdmissionKey.of(base))
                .isNotEqualTo(FileInboxAdmissionKey.of(mutate(base, "repoUrl", "https://example.org/other.git")))
                .isNotEqualTo(FileInboxAdmissionKey.of(mutate(base, "baseBranch", "develop")))
                .isNotEqualTo(FileInboxAdmissionKey.of(mutate(base, "branch", "task/other")));
        assertThat(FileInboxAdmissionKey.of(base))
                .isNotEqualTo(FileInboxAdmissionKey.of(withAcceptance(base, "one more criterion")));
        assertThat(FileInboxAdmissionKey.of(base))
                .isNotEqualTo(FileInboxAdmissionKey.of(withConstraint(base, "allow_paths", List.of("src/"))));
        assertThat(FileInboxAdmissionKey.of(base))
                .isNotEqualTo(FileInboxAdmissionKey.of(mutate(base, "notes", "operator note")));
    }

    @Test
    void keyFormatIsStableAndBounded() {
        String key = FileInboxAdmissionKey.of(taskPayload("TASK-1", "goal one"));
        assertThat(key).startsWith("file.inbox:");
        assertThat(key).hasSize("file.inbox:".length() + 64);
        assertThat(key.substring("file.inbox:".length())).matches("[0-9a-f]{64}");
    }

    private JsonNode taskPayload(String taskId, String goal) {
        ObjectNode payload = json.createObjectNode();
        payload.put("taskId", taskId);
        payload.put("repoUrl", "https://example.com/repo.git");
        payload.put("baseBranch", "main");
        payload.put("branch", "task/" + taskId);
        payload.put("goal", goal);
        payload.putArray("acceptance");
        payload.putObject("constraints");
        payload.putNull("notes");
        return payload;
    }

    private JsonNode mutate(JsonNode base, String field, String value) {
        ObjectNode copy = ((ObjectNode) base).deepCopy();
        copy.put(field, value);
        return copy;
    }

    private JsonNode withAcceptance(JsonNode base, String extra) {
        ObjectNode copy = ((ObjectNode) base).deepCopy();
        copy.putArray("acceptance").add(extra);
        return copy;
    }

    private JsonNode withConstraint(JsonNode base, String name, Object value) {
        ObjectNode copy = ((ObjectNode) base).deepCopy();
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put(name, value);
        copy.set("constraints", json.valueToTree(constraints));
        return copy;
    }
}
