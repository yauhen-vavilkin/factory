package org.folio.factory.testfactory.quality;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.hitl.ArtifactAmendmentValidator;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Ensures reviewer amendments to Test Factory artifacts keep the machine-readable
 * structure intact: downstream agents re-parse the frontmatter, so an edit that
 * breaks it must be rejected at decision time (422), not discovered mid-pipeline.
 */
@Component
public class TestFactoryArtifactAmendmentValidator implements ArtifactAmendmentValidator {

    private static final Set<String> FRONTMATTER_ARTIFACTS =
            Set.of("scope_manifest.md", "test_plan.md", "test_results.md");

    private final FrontmatterCodec frontmatterCodec;
    private final ScriptBundleCodec bundleCodec;

    public TestFactoryArtifactAmendmentValidator(FrontmatterCodec frontmatterCodec, ScriptBundleCodec bundleCodec) {
        this.frontmatterCodec = frontmatterCodec;
        this.bundleCodec = bundleCodec;
    }

    @Override
    public void validate(String artifactName, String content) {
        try {
            if (FRONTMATTER_ARTIFACTS.contains(artifactName)) {
                frontmatterCodec.parse(content);
            } else if ("test_scripts.md".equals(artifactName)) {
                bundleCodec.parse(content);
            }
        } catch (ArtifactFormatException e) {
            throw new IllegalArgumentException("Amended '" + artifactName
                    + "' is not structurally valid: " + e.getMessage(), e);
        }
    }
}
