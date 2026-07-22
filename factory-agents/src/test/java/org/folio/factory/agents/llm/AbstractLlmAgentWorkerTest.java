package org.folio.factory.agents.llm;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractLlmAgentWorkerTest {

    record Summary(String title, List<String> points) {
    }

    static class TestWorker extends AbstractLlmAgentWorker {

        TestWorker(ChatClient chatClient) {
            super(chatClient);
        }

        @Override
        public String id() {
            return "test-worker";
        }

        @Override
        public AgentResult execute(AgentContext context) {
            Summary summary = callForEntity(
                    Map.of("context_name", "unit-test", "input", "hello"), Summary.class);
            return resultWithUsage("summary.md", summary.title());
        }
    }

    @Test
    void parsesStructuredOutputIntoRecord() {
        StubChatModel model = new StubChatModel()
                .enqueue("{\"title\": \"Hello\", \"points\": [\"a\", \"b\"]}");
        TestWorker worker = new TestWorker(ChatClient.create(model));

        Summary summary = worker.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class);

        assertThat(summary.title()).isEqualTo("Hello");
        assertThat(summary.points()).containsExactly("a", "b");
    }

    @Test
    void rendersPromptTemplatesWithVariablesAndPreservesJsonBraces() {
        StubChatModel model = new StubChatModel().enqueue("{\"title\": \"t\", \"points\": []}");
        TestWorker worker = new TestWorker(ChatClient.create(model));

        worker.callForEntity(Map.of("context_name", "unit-test", "input", "THE_INPUT"), Summary.class);

        String allText = model.receivedPrompts().getFirst().getContents();
        assertThat(allText).contains("Context: unit-test");
        assertThat(allText).contains("Summarise this input: THE_INPUT");
        assertThat(allText).contains("{\"a\": 1}");
    }

    @Test
    void retriesOnceOnMalformedOutputThenSucceeds() {
        StubChatModel model = new StubChatModel()
                .enqueue("I think the answer is: not json at all")
                .enqueue("{\"title\": \"Recovered\", \"points\": []}");
        TestWorker worker = new TestWorker(ChatClient.create(model));

        Summary summary = worker.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class);

        assertThat(summary.title()).isEqualTo("Recovered");
        assertThat(model.receivedPrompts()).hasSize(2);
        assertThat(model.receivedPrompts().get(1).getContents()).contains("could not be parsed");
    }

    @Test
    void failsAfterSecondMalformedOutput() {
        StubChatModel model = new StubChatModel()
                .enqueue("garbage one")
                .enqueue("garbage two");
        TestWorker worker = new TestWorker(ChatClient.create(model));

        assertThatThrownBy(() -> worker.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("unparseable output twice");
    }

    @Test
    void resultCarriesTokenUsageOfTheModelCall() {
        StubChatModel model = new StubChatModel()
                .enqueue("{\"title\": \"Hello\", \"points\": []}", 120, 45);
        TestWorker worker = new TestWorker(ChatClient.create(model));

        AgentResult result = worker.execute(null);

        assertThat(result.metrics()).containsEntry("promptTokens", 120L)
                .containsEntry("completionTokens", 45L);
    }

    @Test
    void resultAfterParseRetryCarriesTheSuccessfulCallsUsage() {
        StubChatModel model = new StubChatModel()
                .enqueue("not json", 10, 5)
                .enqueue("{\"title\": \"Recovered\", \"points\": []}", 200, 80);
        TestWorker worker = new TestWorker(ChatClient.create(model));

        AgentResult result = worker.execute(null);

        assertThat(result.metrics()).containsEntry("promptTokens", 200L)
                .containsEntry("completionTokens", 80L);
    }

    @Test
    void zeroUsageLeavesMetricsEmpty() {
        StubChatModel model = new StubChatModel().enqueue("{\"title\": \"t\", \"points\": []}");
        TestWorker worker = new TestWorker(ChatClient.create(model));

        AgentResult result = worker.execute(null);

        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void usageDoesNotLeakAcrossCallsOnTheSameThread() {
        // Simulates a worker that captured usage and then threw before building
        // its result: the next call on the same pooled thread must start clean.
        TestWorker first = new TestWorker(ChatClient.create(
                new StubChatModel().enqueue("{\"title\": \"t\", \"points\": []}", 120, 45)));
        first.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class);

        TestWorker second = new TestWorker(ChatClient.create(new StubChatModel()));
        assertThatThrownBy(() -> second.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class))
                .isInstanceOf(IllegalStateException.class);

        assertThat(second.resultWithUsage("out.md", "content").metrics()).isEmpty();
    }

    @Test
    void nonParseFailurePropagatesWithoutRetry() {
        StubChatModel model = new StubChatModel();
        TestWorker worker = new TestWorker(ChatClient.create(model));

        assertThatThrownBy(() -> worker.callForEntity(Map.of("context_name", "x", "input", "y"), Summary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no queued response");
        assertThat(model.receivedPrompts()).hasSize(1);
    }

    @Test
    void loadsClasspathPromptsForSystemAndUser() {
        TestWorker worker = new TestWorker(ChatClient.create(new StubChatModel()));

        assertThat(worker.systemPrompt(Map.of("context_name", "unit-test")))
                .contains("You are a test worker. Context: unit-test.");
        assertThat(worker.userPrompt(Map.of("input", "X")))
                .contains("Summarise this input: X");
    }
}
