package org.folio.factory.agents.artifact;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Renders and parses the canonical artifact format: Markdown with a YAML
 * frontmatter block. Frontmatter carries the fields downstream agents re-parse;
 * the body is what human reviewers read and amend at HITL gates.
 */
@Component
public class FrontmatterCodec {

    public static final String DELIMITER = "---";

    private final ObjectMapper yamlMapper = YAMLMapper.builder().build();

    public String render(Object metadata, String body) {
        String yaml = yamlMapper.writeValueAsString(metadata).stripTrailing();
        if (yaml.startsWith(DELIMITER)) {
            yaml = yaml.substring(DELIMITER.length()).stripLeading();
        }
        return DELIMITER + "\n" + yaml + "\n" + DELIMITER + "\n\n" + (body == null ? "" : body.strip()) + "\n";
    }

    public Frontmatter parse(String content) {
        if (content == null) {
            throw new ArtifactFormatException("Artifact content is null");
        }
        String stripped = content.stripLeading();
        if (!stripped.startsWith(DELIMITER)) {
            throw new ArtifactFormatException("Artifact has no YAML frontmatter block (must start with ---)");
        }
        int frontmatterStart = DELIMITER.length();
        int frontmatterEnd = stripped.indexOf("\n" + DELIMITER, frontmatterStart);
        if (frontmatterEnd < 0) {
            throw new ArtifactFormatException("Artifact frontmatter block is not closed with ---");
        }
        String yaml = stripped.substring(frontmatterStart, frontmatterEnd);
        String body = stripped.substring(frontmatterEnd + 1 + DELIMITER.length()).stripLeading();
        JsonNode metadata;
        try {
            metadata = yamlMapper.readTree(yaml);
        } catch (Exception e) {
            throw new ArtifactFormatException("Artifact frontmatter is not valid YAML: " + e.getMessage(), e);
        }
        if (metadata == null || !metadata.isObject()) {
            throw new ArtifactFormatException("Artifact frontmatter must be a YAML mapping");
        }
        return new Frontmatter(metadata, body);
    }

    public <T> T parseMetadata(String content, Class<T> type) {
        Frontmatter frontmatter = parse(content);
        try {
            return yamlMapper.treeToValue(frontmatter.metadata(), type);
        } catch (Exception e) {
            throw new ArtifactFormatException("Artifact frontmatter does not match expected structure "
                    + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
