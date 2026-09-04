package org.folio.factory.sandbox.harness;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

final class StaticToolCallback implements ToolCallback {

  private final ToolDefinition definition;

  StaticToolCallback(ToolDefinition definition) {
    this.definition = definition;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  public String call(String toolInput) {
    throw new UnsupportedOperationException("tool calls are dispatched by CodingHarness");
  }

  public String call(String toolInput, ToolContext toolContext) {
    throw new UnsupportedOperationException("tool calls are dispatched by CodingHarness");
  }
}
