package org.folio.factory.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentContextTest {

    private static final ArtifactContent SCOPE =
            new ArtifactContent("scope.md", 1, "text/markdown", "# Scope");

    private AgentContext context(Map<String, ArtifactContent> inputs, Map<String, Object> config) {
        return new AgentContext(UUID.randomUUID(), "triage", inputs, null, config, List.of("out.md"));
    }

    @Test
    void requireInputReturnsDeclaredArtifact() {
        AgentContext ctx = context(Map.of("scope.md", SCOPE), null);
        assertThat(ctx.requireInput("scope.md")).isSameAs(SCOPE);
    }

    @Test
    void requireInputFailsNamingStepAndArtifact() {
        AgentContext ctx = context(Map.of(), null);
        assertThatThrownBy(() -> ctx.requireInput("missing.md"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Step 'triage'")
                .hasMessageContaining("no input artifact 'missing.md'");
    }

    @Test
    void configStringFallsBackToDefaultAndStringifiesValues() {
        AgentContext ctx = context(null, Map.of("max_scenarios", 5));
        assertThat(ctx.configString("absent", "fallback")).isEqualTo("fallback");
        assertThat(ctx.configString("max_scenarios", "0")).isEqualTo("5");
    }

    @Test
    void nullCollectionsBecomeEmpty() {
        AgentContext ctx = new AgentContext(UUID.randomUUID(), "s", null, null, null, null);
        assertThat(ctx.inputs()).isEmpty();
        assertThat(ctx.config()).isEmpty();
        assertThat(ctx.expectedOutputs()).isEmpty();
    }

    @Test
    void collectionsAreImmutable() {
        AgentContext ctx = context(Map.of("scope.md", SCOPE), Map.of("k", "v"));
        assertThatThrownBy(() -> ctx.inputs().put("x", SCOPE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ctx.config().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ctx.expectedOutputs().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
