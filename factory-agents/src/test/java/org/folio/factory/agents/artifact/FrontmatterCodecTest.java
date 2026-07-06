package org.folio.factory.agents.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontmatterCodecTest {

    private final FrontmatterCodec codec = new FrontmatterCodec();

    @Test
    void roundTripsMetadataAndBody() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", "ERM-123");
        metadata.put("components", List.of("agreements", "licenses"));
        metadata.put("risk_level", "medium");

        String rendered = codec.render(metadata, "# Scope\n\nSome analysis.");
        Frontmatter parsed = codec.parse(rendered);

        assertThat(parsed.metadata().get("issue_key").asString()).isEqualTo("ERM-123");
        assertThat(parsed.metadata().get("components").get(1).asString()).isEqualTo("licenses");
        assertThat(parsed.body()).startsWith("# Scope").contains("Some analysis.");
    }

    @Test
    void parsesHumanEditedContentWithWindowsLineEndingsInBody() {
        String content = """
                ---
                issue_key: ERM-9
                ---

                Edited by a reviewer.
                """;
        Frontmatter parsed = codec.parse(content);
        assertThat(parsed.metadata().get("issue_key").asString()).isEqualTo("ERM-9");
        assertThat(parsed.body()).isEqualTo("Edited by a reviewer.\n");
    }

    @Test
    void rejectsContentWithoutFrontmatter() {
        assertThatThrownBy(() -> codec.parse("just markdown, no frontmatter"))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("no YAML frontmatter");
    }

    @Test
    void rejectsUnclosedFrontmatter() {
        assertThatThrownBy(() -> codec.parse("---\nkey: value\n\nbody"))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void rejectsInvalidYamlFrontmatter() {
        assertThatThrownBy(() -> codec.parse("---\n: : :\n---\nbody"))
                .isInstanceOf(ArtifactFormatException.class);
    }

    record ScopeMeta(String issueKey, List<String> components) {
    }

    @Test
    void bindsMetadataToRecord() {
        String content = """
                ---
                issueKey: ERM-7
                components: [a, b]
                ---
                body
                """;
        ScopeMeta meta = codec.parseMetadata(content, ScopeMeta.class);
        assertThat(meta.issueKey()).isEqualTo("ERM-7");
        assertThat(meta.components()).containsExactly("a", "b");
    }
}
