package org.folio.factory.core.limits;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DedupKeyDeriverTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private DedupKeyDeriver deriver(boolean enabled, List<String> pointers) {
        return new DedupKeyDeriver(
                new LimitsProperties(null, null, null, null,
                        new LimitsProperties.Dedup(enabled, null, pointers)),
                json);
    }

    @Test
    void constructor_invalidPointerSyntax_failsFastNamingThePointer() {
        // A malformed configured pointer must fail at startup, not throw on every
        // trigger at runtime (persistent 500s until someone reads the stack trace).
        assertThatThrownBy(() -> deriver(true, List.of("issueKey")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issueKey")
                .hasMessageContaining("factory.limits.dedup.id-pointers");
    }

    @Test
    void derive_dedupDisabled_returnsNull() {
        assertThat(deriver(false, List.of("/issueKey"))
                .derive(json.readTree("{\"issueKey\":\"ERM-1\"}"))).isNull();
    }

    @Test
    void derive_firstResolvingPointerWins() {
        DedupKeyDeriver deriver = deriver(true, List.of("/missing", "/issueKey"));

        assertThat(deriver.derive(json.readTree("{\"issueKey\":\"ERM-1\"}")))
                .isEqualTo("/issueKey=ERM-1");
    }

    @Test
    void derive_blankScalar_fallsThroughToNextPointer() {
        DedupKeyDeriver deriver = deriver(true, List.of("/empty", "/issueKey"));

        assertThat(deriver.derive(json.readTree("{\"empty\":\"  \",\"issueKey\":\"ERM-1\"}")))
                .isEqualTo("/issueKey=ERM-1");
    }

    @Test
    void derive_pointerTargetsNonScalar_fallsBackToPayloadHash() {
        DedupKeyDeriver deriver = deriver(true, List.of("/issue"));

        String key = deriver.derive(json.readTree("{\"issue\":{\"key\":\"ERM-1\"}}"));

        assertThat(key).startsWith("sha256:").hasSize("sha256:".length() + 64);
    }

    @Test
    void derive_noPointerResolves_sha256IsStablePerPayload() {
        DedupKeyDeriver deriver = deriver(true, List.of("/missing"));

        String first = deriver.derive(json.readTree("{\"a\":1}"));
        String again = deriver.derive(json.readTree("{\"a\":1}"));
        String other = deriver.derive(json.readTree("{\"a\":2}"));

        assertThat(first).startsWith("sha256:").isEqualTo(again).isNotEqualTo(other);
    }

    @Test
    void derive_nullPayload_hashesEmptyBytes() {
        assertThat(deriver(true, List.of("/issueKey")).derive(null)).startsWith("sha256:");
    }

    @Test
    void derive_resolvedValueOver255Chars_boundedByHashing() {
        DedupKeyDeriver deriver = deriver(true, List.of("/issueKey"));

        String key = deriver.derive(json.readTree("{\"issueKey\":\"" + "x".repeat(300) + "\"}"));

        assertThat(key).startsWith("sha256:");
        assertThat(key.length()).isLessThanOrEqualTo(255);
    }
}
