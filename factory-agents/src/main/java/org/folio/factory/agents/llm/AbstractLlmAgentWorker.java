package org.folio.factory.agents.llm;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * Base class for LLM-backed agent workers. Provider-agnostic: workers only see
 * the Spring AI {@link ChatClient}; the concrete model (Anthropic, OpenAI, …) is
 * chosen by configuration. Prompts live at
 * {@code classpath:prompts/{workerId}/system.md} and {@code user.md}.
 *
 * <p>Includes one worker-internal retry for malformed structured output. This is
 * distinct from the engine's step retry budget: a parse retry re-asks the model
 * within the same step attempt.</p>
 */
public abstract class AbstractLlmAgentWorker implements AgentWorker {

    private static final Logger log = LoggerFactory.getLogger(AbstractLlmAgentWorker.class);

    protected final ChatClient chatClient;

    protected AbstractLlmAgentWorker(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    protected String systemPrompt(Map<String, Object> variables) {
        return PromptLoader.render(PromptLoader.load(id(), "system"), variables);
    }

    protected String userPrompt(Map<String, Object> variables) {
        return PromptLoader.render(PromptLoader.load(id(), "user"), variables);
    }

    /**
     * Calls the model and binds the response to {@code type} via Spring AI's
     * structured output support, retrying once on unparseable output.
     */
    protected <T> T callForEntity(Map<String, Object> variables, Class<T> type) {
        String system = systemPrompt(variables);
        String user = userPrompt(variables);
        try {
            return chatClient.prompt().system(system).user(user).call().entity(type);
        } catch (RuntimeException firstFailure) {
            log.warn("Worker '{}' got unparseable structured output ({}); retrying once",
                    id(), firstFailure.getMessage());
            try {
                return chatClient.prompt()
                        .system(system)
                        .user(user + "\n\nIMPORTANT: your previous response could not be parsed. "
                                + "Respond with ONLY the requested JSON — no prose, no code fences.")
                        .call()
                        .entity(type);
            } catch (RuntimeException secondFailure) {
                throw new AgentExecutionException("Worker '" + id() + "' produced unparseable output twice: "
                        + secondFailure.getMessage(), secondFailure);
            }
        }
    }

    protected String callForText(Map<String, Object> variables) {
        return chatClient.prompt()
                .system(systemPrompt(variables))
                .user(userPrompt(variables))
                .call()
                .content();
    }
}
