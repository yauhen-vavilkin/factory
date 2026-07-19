package org.folio.factory.app;

import org.folio.factory.agents.prompt.PromptOverride;
import org.folio.factory.agents.prompt.PromptOverrideRepository;
import org.folio.factory.agents.prompt.PromptService;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.core.trigger.PipelineRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Proves the {@code @Autowired(required = false)} {@link org.folio.factory.agents.prompt.PromptResolver}
 * seam actually rewires the prompt an LLM worker sends, end to end through Spring
 * wiring and the engine: a DB override changes what the model receives, and a
 * revert restores the bundled default. This is the one path nothing else covers —
 * the resolver is injected into every {@code AbstractLlmAgentWorker} by type.
 *
 * <p>The override deliberately keeps the bundled triage prompt (so the scripted
 * model still recognises the "Triage Agent" marker and returns valid output) but
 * appends a unique marker; a recording ChatModel captures every prompt actually
 * sent so the test can assert on the marker's presence and, after revert, absence.</p>
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "factory.engine.poll-interval-ms=250"
})
@Import(PromptOverrideEndToEndTest.RecordingLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PromptOverrideEndToEndTest {

    private static final String OVERRIDE_MARKER = "OVERRIDE-MARKER-42";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    PipelineRouter router;

    @Autowired
    StateManager stateManager;

    @Autowired
    PromptService promptService;

    @Autowired
    PromptOverrideRepository promptOverrides;

    @Autowired
    RecordingChatModel chatModel;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void databaseOverrideChangesThePromptTheModelReceivesAndRevertRestoresDefault() {
        // Save an override that still contains the script's "Triage Agent" trigger
        // (so the scripted model returns valid triage output) plus a unique marker.
        String overrideContent = promptService.defaultContent("triage-agent", "system")
                + "\n" + OVERRIDE_MARKER;
        assertThat(overrideContent).contains("Triage Agent");
        promptService.saveOverride("triage-agent", "system", overrideContent, "qa-lead");

        UUID first = trigger();
        // Parking at gate 1 means triage + test-spec have run and nothing else will
        // call the model until a human decides — a quiescent point to assert on.
        awaitParked(first);
        assertThat(chatModel.recorded())
                .as("the triage prompt actually sent must carry the DB override")
                .anyMatch(sent -> sent.contains(OVERRIDE_MARKER));

        // Revert to the bundled default; the next run's triage prompt must lose the marker.
        promptService.revertToDefault("triage-agent", "system", "qa-lead");
        chatModel.clear();

        UUID second = trigger();
        awaitParked(second);
        assertThat(chatModel.recorded())
                .as("triage must have run again after the revert")
                .anyMatch(sent -> sent.contains("Triage Agent"));
        assertThat(chatModel.recorded())
                .as("no prompt sent after the revert may still carry the override marker")
                .noneMatch(sent -> sent.contains(OVERRIDE_MARKER));
    }

    @Test
    void duplicateOverrideVersionViolatesUniqueConstraint() {
        // uq_prompt_override guarantees a single row per (worker, prompt, version);
        // the insert-only store relies on it to keep version numbering unambiguous.
        promptOverrides.save(new PromptOverride("constraint-probe", "system", 1, false, "first", "qa"));

        assertThatThrownBy(() ->
                promptOverrides.save(new PromptOverride("constraint-probe", "system", 1, false, "second", "qa")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID trigger() {
        return router.routeManual("test-factory", json.readTree("""
                {"issueKey": "ERM-2002",
                 "issue": {"key": "ERM-2002",
                           "fields": {"summary": "Inline story",
                                      "description": "AC1: something works",
                                      "status": {"name": "Ready for QA"}}}}
                """));
    }

    private void awaitParked(UUID executionId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus())
                        .isEqualTo(ExecutionStatus.AWAITING_HITL));
    }

    /**
     * A {@link ChatModel} that records the flattened contents of every prompt sent
     * before delegating to the shared scripted model.
     */
    static class RecordingChatModel implements ChatModel {

        private final ChatModel delegate = new StubLlmConfiguration.ScriptedChatModel();
        private final List<String> contents = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            contents.add(prompt.getContents());
            return delegate.call(prompt);
        }

        List<String> recorded() {
            return List.copyOf(contents);
        }

        void clear() {
            contents.clear();
        }
    }

    @TestConfiguration
    static class RecordingLlmConfiguration {

        @Bean
        RecordingChatModel stubChatModel() {
            return new RecordingChatModel();
        }

        @Bean
        ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
            return ChatClient.builder(chatModel);
        }
    }
}
