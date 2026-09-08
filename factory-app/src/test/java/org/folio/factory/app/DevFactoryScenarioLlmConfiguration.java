package org.folio.factory.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Scripted ChatModel for the T16 dev-factory end-to-end scenario. The T13
 * harness rebuilds the prompt each turn with exactly one ToolResponseMessage
 * per completed tool turn, so the number of tool-response messages in the
 * instruction list identifies the turn: read README.md, apply the scenario
 * patch, inspect the resulting diff, then stop with a plain report line.
 */
@TestConfiguration
public class DevFactoryScenarioLlmConfiguration {

    static final String README_PATCH = String.join("\n",
        "diff --git a/README.md b/README.md",
        "--- a/README.md",
        "+++ b/README.md",
        "@@ -1,3 +1,4 @@",
        " # Demo repository",
        " ",
        " Baseline content for the T16 scenario.",
        "+T16 scenario change.",
        "");

    /** T24: the discriminating check the task file declares as mandatory. */
    static final String VERIFY_CMD = "cd repo && grep -n 'T16 scenario change.' README.md";

    static final class ScriptedCodingChatModel implements ChatModel {

        private final ObjectMapper mapper = JsonMapper.builder().build();

        @Override
        public ChatResponse call(Prompt prompt) {
            long completedToolTurns = prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).count();
            AssistantMessage assistant = switch ((int) completedToolTurns) {
                case 0 -> toolCall("call-1", "read", args().put("path", "repo/README.md"));
                case 1 -> toolCall("call-2", "apply_patch", args().put("diff", README_PATCH));
                case 2 -> toolCall("call-3", "git_diff", args());
                case 3 -> toolCall("call-4", "exec", args().put("cmd", VERIFY_CMD));
                default -> new AssistantMessage("Scenario complete: README.md updated.");
            };
            return new ChatResponse(List.of(new Generation(assistant)));
        }

        private ObjectNode args() {
            return mapper.createObjectNode();
        }

        private static AssistantMessage toolCall(String id, String name, ObjectNode args) {
            return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args.toString())))
                .build();
        }
    }

    @Bean
    public ChatModel scenarioChatModel() {
        return new ScriptedCodingChatModel();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
