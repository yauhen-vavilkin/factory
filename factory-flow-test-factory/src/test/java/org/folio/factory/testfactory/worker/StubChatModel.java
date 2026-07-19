package org.folio.factory.testfactory.worker;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted ChatModel: returns queued responses in order and records prompts.
 */
public class StubChatModel implements ChatModel {

    private final List<String> queuedResponses = new ArrayList<>();
    private final List<Prompt> receivedPrompts = new ArrayList<>();

    public StubChatModel enqueue(String response) {
        queuedResponses.add(response);
        return this;
    }

    public List<Prompt> receivedPrompts() {
        return receivedPrompts;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        receivedPrompts.add(prompt);
        if (queuedResponses.isEmpty()) {
            throw new IllegalStateException("StubChatModel has no queued response");
        }
        String next = queuedResponses.removeFirst();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(next))));
    }
}
