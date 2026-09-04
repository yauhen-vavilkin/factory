package org.folio.factory.sandbox.harness;

import java.util.List;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.tools.ApplyPatchTool;
import org.folio.factory.sandbox.tools.ExecTool;
import org.folio.factory.sandbox.tools.GitDiffTool;
import org.folio.factory.sandbox.tools.ListTool;
import org.folio.factory.sandbox.tools.ReadTool;
import org.folio.factory.sandbox.tools.TestTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ToolDispatcher {

  static final List<String> TOOL_NAMES =
      List.of("read", "list", "apply_patch", "exec", "git_diff", "test");

  private final ReadTool readTool;
  private final ListTool listTool;
  private final ApplyPatchTool applyPatchTool;
  private final ExecTool execTool;
  private final GitDiffTool gitDiffTool;
  private final TestTool testTool;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public ToolDispatcher(ReadTool readTool, ListTool listTool, ApplyPatchTool applyPatchTool,
      ExecTool execTool, GitDiffTool gitDiffTool, TestTool testTool) {
    this.readTool = readTool;
    this.listTool = listTool;
    this.applyPatchTool = applyPatchTool;
    this.execTool = execTool;
    this.gitDiffTool = gitDiffTool;
    this.testTool = testTool;
  }

  public List<ToolCallback> toolCallbacks() {
    return List.of(
        callback("read",
            "Read up to 2000 lines of a file from the sandbox workspace; use fromLine/toLine "
                + "to read large files in chunks",
            "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"file path relative to /workspace, "
                + "e.g. repo/pom.xml\"},"
                + "\"fromLine\":{\"type\":\"integer\",\"minimum\":1},"
                + "\"toLine\":{\"type\":\"integer\",\"minimum\":1}},"
                + "\"required\":[\"path\"],\"additionalProperties\":false}"),
        callback("list",
            "List files under a directory, optionally filtered by glob; sorted, max 500 entries",
            "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\"},"
                + "\"glob\":{\"type\":\"string\"}},"
                + "\"required\":[\"path\"],\"additionalProperties\":false}"),
        callback("apply_patch",
            "Apply a unified diff (git diff format) to the repository; atomic: applied only if it "
                + "passes git apply --check",
            "{\"type\":\"object\",\"properties\":{"
                + "\"diff\":{\"type\":\"string\",\"description\":\"unified diff in git diff format\"}},"
                + "\"required\":[\"diff\"],\"additionalProperties\":false}"),
        callback("exec",
            "Run a shell command in /workspace; one command per call; no cd/env persistence "
                + "between calls",
            "{\"type\":\"object\",\"properties\":{"
                + "\"cmd\":{\"type\":\"string\"},"
                + "\"timeoutSec\":{\"type\":\"integer\",\"minimum\":1}},"
                + "\"required\":[\"cmd\"],\"additionalProperties\":false}"),
        callback("git_diff",
            "Show git status and the full diff of the working tree (what will be submitted)",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
        callback("test",
            "Run Maven tests, optionally for one module (mvn -pl <module> -am test -B); returns "
                + "parsed surefire results",
            "{\"type\":\"object\",\"properties\":{"
                + "\"module\":{\"type\":\"string\",\"description\":\"maven module id, e.g. "
                + "factory-core; omit for all modules\"}},"
                + "\"additionalProperties\":false}"));
  }

  public ToolExecution execute(SandboxHandle handle, String toolName, String argumentsJson) {
    if (toolName == null || !TOOL_NAMES.contains(toolName)) {
      return ToolExecution.invalidToolCall("unknown tool '" + toolName
          + "'; available tools: " + String.join(", ", TOOL_NAMES));
    }
    JsonNode args;
    try {
      args = mapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
    } catch (JacksonException e) {
      return ToolExecution.invalidToolCall("arguments are not valid JSON: " + e.getMessage());
    }
    try {
      return switch (toolName) {
        case "read" -> ToolExecution.executed(readTool.read(handle,
            requiredString(args, "path"), intArg(args, "fromLine"), intArg(args, "toLine")));
        case "list" -> ToolExecution.executed(listTool.list(handle,
            requiredString(args, "path"), optionalString(args, "glob")));
        case "apply_patch" -> ToolExecution.executed(
            applyPatchTool.apply(handle, requiredString(args, "diff")));
        case "exec" -> ToolExecution.executed(execTool.run(handle,
            requiredString(args, "cmd"), longArg(args, "timeoutSec")));
        case "git_diff" -> ToolExecution.executed(gitDiffTool.diff(handle));
        case "test" -> ToolExecution.executed(testTool.test(handle, optionalString(args, "module")));
        default -> ToolExecution.invalidToolCall("unknown tool '" + toolName + "'");
      };
    } catch (InvalidArgsException e) {
      return ToolExecution.invalidToolCall(e.getMessage()
          + " | fix the arguments and call the tool again with a valid JSON object");
    }
  }

  static final class InvalidArgsException extends RuntimeException {

    InvalidArgsException(String message) {
      super(message);
    }
  }

  private static String requiredString(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      throw new InvalidArgsException("missing required string field '" + field + "'");
    }
    if (!node.isString()) {
      throw new InvalidArgsException("field '" + field + "' must be a JSON string");
    }
    return node.textValue();
  }

  private static String optionalString(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new InvalidArgsException("field '" + field + "' must be a JSON string");
    }
    return node.textValue();
  }

  private static Integer intArg(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isNumber() || !node.canConvertToInt()) {
      throw new InvalidArgsException("field '" + field + "' must be an integer");
    }
    return node.intValue();
  }

  private static Long longArg(JsonNode args, String field) {
    JsonNode node = args.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isNumber() || !node.canConvertToLong()) {
      throw new InvalidArgsException("field '" + field + "' must be a number");
    }
    return node.longValue();
  }

  private static ToolCallback callback(String name, String description, String schema) {
    ToolDefinition definition = DefaultToolDefinition.builder()
        .name(name)
        .description(description)
        .inputSchema(schema)
        .build();
    return new StaticToolCallback(definition);
  }
}
