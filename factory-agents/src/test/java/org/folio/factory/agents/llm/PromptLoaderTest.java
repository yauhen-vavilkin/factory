package org.folio.factory.agents.llm;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    @Test
    void loadsPromptFromClasspath() {
        assertThat(PromptLoader.load("test-worker", "system"))
                .contains("{{context_name}}");
    }

    @Test
    void failsWithLocationWhenPromptMissing() {
        assertThatThrownBy(() -> PromptLoader.load("no-such-worker", "system"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("prompts/no-such-worker/system.md");
    }

    @Test
    void leavesUnknownPlaceholderVisible() {
        assertThat(PromptLoader.render("Hello {{missing}}!", Map.of()))
                .isEqualTo("Hello {{missing}}!");
    }

    @Test
    void rendersNullValueAsEmpty() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", null);
        assertThat(PromptLoader.render("Value: [{{input}}]", vars))
                .isEqualTo("Value: []");
    }

    @Test
    void rendersRegexSpecialCharactersLiterally() {
        assertThat(PromptLoader.render("Price: {{v}}", Map.of("v", "$1 and C:\\path\\to")))
                .isEqualTo("Price: $1 and C:\\path\\to");
    }

    @Test
    void doesNotReExpandPlaceholdersInSubstitutedValues() {
        Map<String, Object> vars = Map.of("a", "before {{other}} after", "other", "SHOULD NOT APPEAR");
        assertThat(PromptLoader.render("{{a}}", vars))
                .isEqualTo("before {{other}} after");
    }
}
