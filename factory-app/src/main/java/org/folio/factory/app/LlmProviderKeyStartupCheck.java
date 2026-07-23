package org.folio.factory.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Warns at startup when the active LLM provider has no API key. The app boots
 * fine without one, but the first AGENT step then fails mid-run and escalates
 * to review minutes later — this surfaces the misconfiguration immediately.
 * Deliberately not a hard failure: tests and advisory setups must still boot.
 */
@Component
class LlmProviderKeyStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderKeyStartupCheck.class);

    LlmProviderKeyStartupCheck(@Value("${spring.ai.model.chat:anthropic}") String provider,
                               @Value("${spring.ai.anthropic.api-key:}") String anthropicKey,
                               @Value("${spring.ai.openai.api-key:}") String openAiKey) {
        String warning = missingKeyWarning(provider, anthropicKey, openAiKey);
        if (warning != null) {
            log.warn(warning);
        }
    }

    static String missingKeyWarning(String provider, String anthropicKey, String openAiKey) {
        String envVar = switch (provider) {
            case "anthropic" -> anthropicKey.isBlank() ? "ANTHROPIC_API_KEY" : null;
            // "unused" is application.yaml's placeholder default for the openai
            // provider — an unset FACTORY_LLM_API_KEY arrives as that literal.
            case "openai" -> openAiKey.isBlank() || "unused".equals(openAiKey) ? "FACTORY_LLM_API_KEY" : null;
            default -> null;
        };
        if (envVar == null) {
            return null;
        }
        return "LLM provider '" + provider + "' has no API key — set " + envVar
                + " or LLM-backed steps will fail at runtime and escalate to human review"
                + " (ignore if the endpoint needs no key, e.g. local Ollama)";
    }
}
