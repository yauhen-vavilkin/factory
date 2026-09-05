package org.folio.factory.devfactory.inbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses a task file from the dev-factory inbox: strict eight-key schema,
 * defaults for base/branch/acceptance/constraints, and git refname/SHA
 * validation of the resolved base and branch.
 */
public final class InboxTaskFileParser {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id", "repo", "base", "branch", "goal", "acceptance", "constraints", "notes");

    private final YAMLMapper yamlMapper;

    public InboxTaskFileParser() {
        this.yamlMapper = new YAMLMapper();
    }

    public InboxTask parse(Path file) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(Files.readAllBytes(file));
        } catch (IOException | JacksonException e) {
            throw new InboxTaskFileException("Cannot read task file " + file + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new InboxTaskFileException("Task file " + file + " must contain a YAML mapping");
        }
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!ALLOWED_KEYS.contains(field.getKey())) {
                throw new InboxTaskFileException(
                        "Unknown top-level key '" + field.getKey() + "' in task file " + file);
            }
        }
        String id = requiredText(root, "id", file);
        String repo = requiredText(root, "repo", file);
        String goal = requiredText(root, "goal", file);
        String base = optionalText(root, "base", file);
        String branch = optionalText(root, "branch", file);
        List<String> acceptance = stringList(root, "acceptance", file);
        Map<String, Object> constraints = constraintsMap(root, "constraints", file);
        String notes = optionalText(root, "notes", file);

        if (base == null) {
            base = "main";
        }
        if (branch == null) {
            branch = "task/" + id;
        }
        validateRef(file, "base", base);
        validateRef(file, "branch", branch);
        return new InboxTask(id, repo, base, branch, goal, acceptance, constraints, notes);
    }

    private static String requiredText(JsonNode root, String field, Path file) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": missing required field '" + field + "'");
        }
        if (!node.isTextual()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' must be a string");
        }
        String value = node.asString();
        if (value.isBlank()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' must not be blank");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field, Path file) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' must be a string");
        }
        return node.asString();
    }

    private static List<String> stringList(JsonNode root, String field, Path file) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' must be a list of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new InboxTaskFileException(
                        "Task file " + file + ": field '" + field + "' must contain only strings");
            }
            values.add(item.asString());
        }
        return values;
    }

    private static Map<String, Object> constraintsMap(JsonNode root, String field, Path file) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' must be a mapping");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            values.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return values;
    }

    private static Object toJavaValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(toJavaValue(item));
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            map.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return map;
    }

    private static void validateRef(Path file, String field, String ref) {
        if (!GitRefs.isValidBranchName(ref) || GitRefs.isRawCommitId(ref)) {
            throw new InboxTaskFileException(
                    "Task file " + file + ": field '" + field + "' value '" + ref
                            + "' is not a usable branch name");
        }
    }
}
