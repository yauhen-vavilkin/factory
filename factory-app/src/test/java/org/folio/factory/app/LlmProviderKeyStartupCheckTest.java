package org.folio.factory.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderKeyStartupCheckTest {

    @Test
    void warnsForAnthropicProviderWithoutKey() {
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("anthropic", "", "whatever"))
                .contains("ANTHROPIC_API_KEY");
    }

    @Test
    void warnsForOpenAiProviderWithoutKey() {
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("openai", "", ""))
                .contains("FACTORY_LLM_API_KEY");
    }

    @Test
    void warnsForOpenAiProviderWithTheUnusedYamlPlaceholder() {
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("openai", "", "unused"))
                .contains("FACTORY_LLM_API_KEY");
    }

    @Test
    void silentWhenTheActiveProviderHasAKey() {
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("anthropic", "sk-ant-x", "")).isNull();
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("openai", "", "gsk_real")).isNull();
    }

    @Test
    void silentForNonLlmProviders() {
        assertThat(LlmProviderKeyStartupCheck.missingKeyWarning("none", "", "")).isNull();
    }
}
