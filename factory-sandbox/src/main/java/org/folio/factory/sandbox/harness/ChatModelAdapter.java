package org.folio.factory.sandbox.harness;

import java.util.List;

public interface ChatModelAdapter {

  ModelReply reply(String systemPrompt, String taskGoal, List<ChatMessage> history);
}
