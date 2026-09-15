package org.folio.factory.devfactory.inbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.folio.factory.devfactory.contract.TaskRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Strict and bounded parser for TaskRequest/v1 plus the historical eight-key format. */
public final class InboxTaskFileParser {
  public static final int MAX_BYTES = 256 * 1024;

  private static final Set<String> V1_KEYS = Set.of("schemaVersion", "source", "repository",
      "baseRevision", "baseRef", "profileId", "verificationPlanId", "runKey",
      "deliveryMode", "metadata", "goal", "acceptanceCriteria", "constraints", "notes", "decisions");
  private static final Set<String> SOURCE_KEYS = Set.of("type", "id", "project", "component");
  private static final Set<String> ACCEPTANCE_KEYS = Set.of("id", "text", "source");
  private static final Set<String> DECISION_KEYS = Set.of("id", "category", "question", "whyItMatters",
      "options", "recommendedOptionId", "recommendationRationale", "evidence");
  private static final Set<String> OPTION_KEYS = Set.of("id", "label", "consequence");
  private static final Set<String> EVIDENCE_KEYS = Set.of("path", "terms");
  /** Decision classes a human must own; engineering uncertainty is deliberately absent. */
  public static final Set<String> DECISION_CATEGORIES = Set.of("REQUIREMENTS_CONFLICT",
      "PRODUCT_SEMANTICS", "ARCHITECTURE_CHOICE", "SCOPE");
  private static final String IDENTIFIER = "[A-Za-z0-9._-]{1,64}";
  private static final Set<String> LEGACY_KEYS = Set.of(
      "id", "repo", "base", "branch", "goal", "acceptance", "constraints", "notes");
  private static final Set<String> FORBIDDEN_CONFIGURATION_KEYS = Set.of(
      "command", "commands", "image", "imagedigest", "mount", "mounts", "credential",
      "credentials", "secret", "secrets", "network", "networkpolicy", "workdir",
      "environment", "env", "verificationcommand", "buildcommand");

  private final YAMLMapper yamlMapper = YAMLMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  public TaskRequest parse(Path file) {
    try {
      if (Files.isSymbolicLink(file)) {
        throw invalid(file.toString(), "symbolic links are not accepted");
      }
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw invalid(file.toString(), "input must be a regular file");
      }
      if (Files.size(file) > MAX_BYTES) {
        throw invalid(file.toString(), "input exceeds the 256 KiB limit");
      }
      return parseBytes(Files.readAllBytes(file), file.toString());
    } catch (InboxTaskFileException e) {
      throw e;
    } catch (IOException e) {
      throw new InboxTaskFileException("Cannot read task file " + file + ": " + e.getMessage(), e);
    }
  }

  public TaskRequest parseBytes(byte[] bytes, String sourceName) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw new InboxTaskFileException("Task input " + sourceName + " exceeds the 256 KiB limit");
    }
    String raw = strictUtf8(bytes, sourceName);
    JsonNode root;
    try (JsonParser input = yamlMapper.createParser(bytes)) {
      root = yamlMapper.readTree(input);
      if (input.nextToken() != null) {
        throw invalid(sourceName, "multiple YAML/JSON documents are not accepted");
      }
    } catch (InboxTaskFileException e) {
      throw e;
    } catch (JacksonException e) {
      throw new InboxTaskFileException("Cannot parse task input " + sourceName + ": " + e.getMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new InboxTaskFileException("Task input " + sourceName + " must contain one mapping");
    }
    return root.has("schemaVersion") ? parseV1(root, raw, sourceName) : adaptLegacy(root, raw, sourceName);
  }

  private TaskRequest parseV1(JsonNode root, String raw, String name) {
    rejectUnknown(root, V1_KEYS, "top-level", name);
    int version = requiredInt(root, "schemaVersion", name);
    if (version != TaskRequest.CURRENT_SCHEMA_VERSION) {
      throw invalid(name, "unsupported schemaVersion " + version);
    }
    JsonNode sourceNode = requiredObject(root, "source", name);
    rejectUnknown(sourceNode, SOURCE_KEYS, "source", name);
    TaskRequest.SourceIdentity source = new TaskRequest.SourceIdentity(
        requiredText(sourceNode, "type", name), requiredText(sourceNode, "id", name),
        optionalText(sourceNode, "project", name), optionalText(sourceNode, "component", name));
    String revision = optionalText(root, "baseRevision", name);
    String ref = optionalText(root, "baseRef", name);
    validateRevisionChoice(revision, ref, name);
    JsonNode metadata = objectOrEmpty(root.get("metadata"), "metadata", name);
    JsonNode constraints = objectOrEmpty(root.get("constraints"), "constraints", name);
    rejectConfigurationAuthority(constraints, name);
    return new TaskRequest(version, source, optionalText(root, "repository", name), revision, ref,
        optionalText(root, "profileId", name), optionalText(root, "verificationPlanId", name),
        defaultText(root, "runKey", "default", name),
        defaultText(root, "deliveryMode", "LOCAL_ONLY", name), metadata,
        requiredText(root, "goal", name), acceptance(root.get("acceptanceCriteria"), name),
        constraints, optionalText(root, "notes", name), raw, false,
        decisions(root.get("decisions"), name));
  }

  private TaskRequest adaptLegacy(JsonNode root, String raw, String name) {
    rejectUnknown(root, LEGACY_KEYS, "legacy top-level", name);
    String id = requiredText(root, "id", name);
    String base = requiredText(root, "base", name);
    String revision = GitRefs.isFullCommitSha(base) ? base.toLowerCase(Locale.ROOT) : null;
    String ref = revision == null ? base : null;
    validateRevisionChoice(revision, ref, name);
    JsonNode constraints = objectOrEmpty(root.get("constraints"), "constraints", name);
    rejectConfigurationAuthority(constraints, name);
    List<TaskRequest.AcceptanceCriterion> criteria = new ArrayList<>();
    JsonNode acceptance = root.get("acceptance");
    if (acceptance != null && !acceptance.isNull()) {
      if (!acceptance.isArray()) {
        throw invalid(name, "field 'acceptance' must be a list of strings");
      }
      int index = 1;
      for (JsonNode item : acceptance) {
        if (!item.isTextual() || item.asString().isBlank()) {
          throw invalid(name, "field 'acceptance' must contain non-blank strings");
        }
        criteria.add(new TaskRequest.AcceptanceCriterion("LEGACY-AC-" + index++, item.asString(), "TASK"));
      }
    }
    ObjectNode metadata = jsonMapper.createObjectNode();
    String branch = optionalText(root, "branch", name);
    if (branch != null) {
      if (!GitRefs.isValidBranchName(branch) || GitRefs.isRawCommitId(branch)) {
        throw invalid(name, "legacy field 'branch' is not a usable branch name");
      }
      metadata.put("legacyDeliveryBranch", branch);
    }
    return new TaskRequest(1, new TaskRequest.SourceIdentity("LEGACY_FILE", id,
        projectFromId(id), null), requiredText(root, "repo", name), revision, ref, null, null,
        "default", "LOCAL_ONLY", metadata, requiredText(root, "goal", name), List.copyOf(criteria),
        constraints, optionalText(root, "notes", name), raw, true);
  }

  private static List<TaskRequest.AcceptanceCriterion> acceptance(JsonNode node, String name) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw invalid(name, "field 'acceptanceCriteria' must be a list");
    }
    List<TaskRequest.AcceptanceCriterion> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode item : node) {
      if (!item.isObject()) {
        throw invalid(name, "acceptanceCriteria entries must be mappings");
      }
      rejectUnknown(item, ACCEPTANCE_KEYS, "acceptance criterion", name);
      String id = requiredText(item, "id", name);
      if (!ids.add(id)) {
        throw invalid(name, "duplicate acceptance criterion id '" + id + "'");
      }
      result.add(new TaskRequest.AcceptanceCriterion(id, requiredText(item, "text", name),
          defaultText(item, "source", "TASK", name)));
    }
    return List.copyOf(result);
  }

  /**
   * At most one declared decision per task: one execution pauses at most once
   * (no nested decisions). Every option is concrete and a recommendation must
   * name one of them and say why.
   */
  private static List<TaskRequest.DeclaredDecision> decisions(JsonNode node, String name) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw invalid(name, "field 'decisions' must be a list");
    }
    if (node.size() > 1) {
      throw invalid(name, "at most one declared decision is supported per task");
    }
    List<TaskRequest.DeclaredDecision> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.isObject()) {
        throw invalid(name, "decisions entries must be mappings");
      }
      rejectUnknown(item, DECISION_KEYS, "decision", name);
      String id = identifier(item, "id", name);
      String category = requiredText(item, "category", name);
      if (!DECISION_CATEGORIES.contains(category)) {
        throw invalid(name, "decision category must be one of " + DECISION_CATEGORIES.stream().sorted().toList());
      }
      JsonNode optionNodes = item.get("options");
      if (optionNodes == null || !optionNodes.isArray() || optionNodes.size() < 2 || optionNodes.size() > 5) {
        throw invalid(name, "decision '" + id + "' must declare between 2 and 5 options");
      }
      List<TaskRequest.Option> options = new ArrayList<>();
      Set<String> optionIds = new HashSet<>();
      for (JsonNode option : optionNodes) {
        if (!option.isObject()) {
          throw invalid(name, "decision options must be mappings");
        }
        rejectUnknown(option, OPTION_KEYS, "decision option", name);
        String optionId = identifier(option, "id", name);
        if (!optionIds.add(optionId)) {
          throw invalid(name, "duplicate decision option id '" + optionId + "'");
        }
        options.add(new TaskRequest.Option(optionId, requiredText(option, "label", name),
            requiredText(option, "consequence", name)));
      }
      String recommended = optionalText(item, "recommendedOptionId", name);
      String rationale = optionalText(item, "recommendationRationale", name);
      if (recommended != null && (!optionIds.contains(recommended) || rationale == null || rationale.isBlank())) {
        throw invalid(name, "recommendedOptionId must name a declared option and carry a rationale");
      }
      if (recommended == null && rationale != null) {
        throw invalid(name, "recommendationRationale requires recommendedOptionId");
      }
      result.add(new TaskRequest.DeclaredDecision(id, category, requiredText(item, "question", name),
          requiredText(item, "whyItMatters", name), List.copyOf(options), recommended, rationale,
          evidence(item.get("evidence"), name)));
    }
    return List.copyOf(result);
  }

  private static List<TaskRequest.Evidence> evidence(JsonNode node, String name) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray() || node.size() > 5) {
      throw invalid(name, "decision evidence must be a list of at most 5 entries");
    }
    List<TaskRequest.Evidence> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.isObject()) {
        throw invalid(name, "decision evidence entries must be mappings");
      }
      rejectUnknown(item, EVIDENCE_KEYS, "decision evidence", name);
      String path = requiredText(item, "path", name);
      if (path.startsWith("/") || path.contains("..") || path.contains("\\") || path.length() > 256) {
        throw invalid(name, "decision evidence path is unsafe: " + path);
      }
      JsonNode termNodes = item.get("terms");
      if (termNodes == null || !termNodes.isArray() || termNodes.isEmpty() || termNodes.size() > 5) {
        throw invalid(name, "decision evidence '" + path + "' must declare 1 to 5 terms");
      }
      List<String> terms = new ArrayList<>();
      for (JsonNode term : termNodes) {
        if (!term.isTextual() || term.asString().isBlank() || term.asString().length() > 80) {
          throw invalid(name, "decision evidence terms must be short non-blank strings");
        }
        terms.add(term.asString());
      }
      result.add(new TaskRequest.Evidence(path, List.copyOf(terms)));
    }
    return List.copyOf(result);
  }

  private static String identifier(JsonNode node, String field, String name) {
    String value = requiredText(node, field, name);
    if (!value.matches(IDENTIFIER)) {
      throw invalid(name, "field '" + field + "' must match " + IDENTIFIER);
    }
    return value;
  }

  private static void validateRevisionChoice(String revision, String ref, String name) {
    if ((revision == null) == (ref == null)) {
      throw invalid(name, "exactly one of baseRevision or baseRef is required");
    }
    if (revision != null && !GitRefs.isFullCommitSha(revision)) {
      throw invalid(name, "baseRevision must be a full 40-character commit SHA");
    }
    if (ref != null && (!GitRefs.isValidBranchName(ref) || GitRefs.isRawCommitId(ref))) {
      throw invalid(name, "baseRef must be a safe branch name, not a commit SHA");
    }
  }

  private static void rejectConfigurationAuthority(JsonNode node, String name) {
    if (node == null || (!node.isObject() && !node.isArray())) {
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        String key = entry.getKey().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (FORBIDDEN_CONFIGURATION_KEYS.contains(key)) {
          throw invalid(name, "task constraints cannot define trusted configuration key '"
              + entry.getKey() + "'");
        }
        rejectConfigurationAuthority(entry.getValue(), name);
      }
    } else {
      node.forEach(child -> rejectConfigurationAuthority(child, name));
    }
  }

  private static JsonNode requiredObject(JsonNode root, String field, String name) {
    JsonNode value = root.get(field);
    if (value == null || !value.isObject()) {
      throw invalid(name, "field '" + field + "' must be a mapping");
    }
    return value;
  }

  private JsonNode objectOrEmpty(JsonNode value, String field, String name) {
    if (value == null || value.isNull()) {
      return jsonMapper.createObjectNode();
    }
    if (!value.isObject()) {
      throw invalid(name, "field '" + field + "' must be a mapping");
    }
    return value.deepCopy();
  }

  private static String requiredText(JsonNode root, String field, String name) {
    String value = optionalText(root, field, name);
    if (value == null || value.isBlank()) {
      throw invalid(name, "missing or blank required field '" + field + "'");
    }
    return value;
  }

  private static String optionalText(JsonNode root, String field, String name) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw invalid(name, "field '" + field + "' must be a string");
    }
    return value.asString();
  }

  private static String defaultText(JsonNode root, String field, String fallback, String name) {
    String value = optionalText(root, field, name);
    return value == null ? fallback : value;
  }

  private static int requiredInt(JsonNode root, String field, String name) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber()) {
      throw invalid(name, "field '" + field + "' must be an integer");
    }
    return value.asInt();
  }

  private static void rejectUnknown(JsonNode object, Set<String> allowed, String scope, String name) {
    for (Map.Entry<String, JsonNode> field : object.properties()) {
      if (!allowed.contains(field.getKey())) {
        throw invalid(name, "unknown " + scope + " key '" + field.getKey() + "'");
      }
    }
  }

  private static String strictUtf8(byte[] bytes, String name) {
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new InboxTaskFileException("Task input " + name + " is not valid UTF-8", e);
    }
  }

  private static String projectFromId(String id) {
    int dash = id.indexOf('-');
    return dash > 0 ? id.substring(0, dash).toUpperCase(Locale.ROOT) : null;
  }

  private static InboxTaskFileException invalid(String name, String detail) {
    return new InboxTaskFileException("Task input " + name + ": " + detail);
  }
}
