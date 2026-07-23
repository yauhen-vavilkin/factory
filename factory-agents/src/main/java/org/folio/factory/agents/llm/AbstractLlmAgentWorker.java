package org.folio.factory.agents.llm;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.core.JacksonException;

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

    // Token usage of the last successful model call on this thread. Set per call
    // (never accumulated) and cleared both at callForEntity entry and on read, so
    // a reused engine pool thread cannot leak counts into a later step even when
    // a worker throws after its call. A worker making several calls per execute
    // reports only the last one.
    private static final ThreadLocal<long[]> USAGE = new ThreadLocal<>();

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
        USAGE.remove();
        String system = systemPrompt(variables);
        String user = userPrompt(variables);
        try {
            return captureUsage(chatClient.prompt().system(system).user(user).call().responseEntity(type));
        } catch (JacksonException firstFailure) {
            // JacksonException is what BeanOutputConverter propagates on unparseable
            // output; transport/auth/rate-limit failures must fall through to the
            // engine's step retry instead of triggering a second model call here.
            log.warn("Worker '{}' got unparseable structured output ({}); retrying once",
                    id(), firstFailure.getMessage());
            try {
                return captureUsage(chatClient.prompt()
                        .system(system)
                        .user(user + "\n\nIMPORTANT: your previous response could not be parsed. "
                                + "Respond with ONLY the requested JSON — no prose, no code fences.")
                        .call()
                        .responseEntity(type));
            } catch (JacksonException secondFailure) {
                throw new AgentExecutionException("Worker '" + id() + "' produced unparseable output twice: "
                        + secondFailure.getMessage(), secondFailure);
            }
        }
    }

    private <T> T captureUsage(ResponseEntity<ChatResponse, T> result) {
        Usage usage = result.response() == null ? null : result.response().getMetadata().getUsage();
        if (usage != null) {
            USAGE.set(new long[]{
                    usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                    usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens()});
        }
        return result.entity();
    }

    /**
     * Builds the step result carrying the last model call's token usage as
     * {@code promptTokens}/{@code completionTokens} metrics. Failed attempts
     * (including the internal parse-retry's first attempt) are not observable
     * through the structured-output API and are not counted.
     */
    protected AgentResult resultWithUsage(String artifactName, String content) {
        long[] usage = USAGE.get();
        USAGE.remove();
        if (usage == null || usage[0] + usage[1] == 0) {
            return AgentResult.of(artifactName, content);
        }
        return new AgentResult(Map.of(artifactName, content),
                Map.of("promptTokens", usage[0], "completionTokens", usage[1]));
    }

    protected String callForText(Map<String, Object> variables) {
        return chatClient.prompt()
                .system(systemPrompt(variables))
                .user(userPrompt(variables))
                .call()
                .content();
    }
}
