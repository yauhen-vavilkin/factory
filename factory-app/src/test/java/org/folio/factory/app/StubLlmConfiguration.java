package org.folio.factory.app;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Scripted LLM for end-to-end tests: recognises which Flow A worker is calling
 * by its system prompt and returns canned structured output. No API key needed.
 */
@TestConfiguration
public class StubLlmConfiguration {

    public static final String SCOPE_MANIFEST_JSON = """
            {"issueKey": "ERM-1001",
             "summary": "Add agreement name validation",
             "components": ["mod-agreements"],
             "endpoints": ["/erm/sas"],
             "riskLevel": "medium",
             "ambiguities": ["Maximum name length is not specified"],
             "analysis": "## Analysis\\n\\nThe story adds validation for agreement names on POST /erm/sas."}
            """;

    public static final String TEST_PLAN_JSON = """
            {"issueKey": "ERM-1001",
             "cases": [
               {"id": "TC-01", "title": "Create agreement with valid name", "priority": "high",
                "type": "automatable", "targetEndpoint": "/erm/sas",
                "preconditions": ["Clean test tenant"],
                "steps": ["POST /erm/sas with name 'Test Agreement 001'"],
                "expected": "201 Created with agreement id", "acceptanceCriteriaRef": "AC1"},
               {"id": "TC-02", "title": "Reject agreement without name", "priority": "high",
                "type": "automatable", "targetEndpoint": "/erm/sas",
                "preconditions": [],
                "steps": ["POST /erm/sas with empty body"],
                "expected": "422 Unprocessable Entity", "acceptanceCriteriaRef": "AC2"}
             ]}
            """;

    public static final String SCRIPT_BUNDLE_JSON = """
            {"framework": "karate",
             "files": [
               {"path": "features/agreements.feature",
                "caseIds": ["TC-01", "TC-02"],
                "content": "Feature: Agreement name validation\\n\\n  Background:\\n    * url 'http://localhost:8080'\\n\\n  Scenario: TC-01 create agreement with valid name\\n    Given path '/erm/sas'\\n    And request { name: 'Test Agreement 001' }\\n    When method post\\n    Then status 201\\n\\n  Scenario: TC-02 reject agreement without name\\n    Given path '/erm/sas'\\n    And request {}\\n    When method post\\n    Then status 422\\n"}
             ]}
            """;

    static class ScriptedChatModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            String text = prompt.getContents();
            String response;
            if (text.contains("Triage Agent")) {
                response = SCOPE_MANIFEST_JSON;
            } else if (text.contains("Test Specification Agent")) {
                response = TEST_PLAN_JSON;
            } else if (text.contains("Test Automation Agent")) {
                response = SCRIPT_BUNDLE_JSON;
            } else {
                throw new IllegalStateException("ScriptedChatModel got an unrecognised prompt");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }

    @Bean
    public ChatModel stubChatModel() {
        return new ScriptedChatModel();
    }

    @Bean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
