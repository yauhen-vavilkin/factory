package org.folio.factory.testfactory.quality;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFactoryArtifactAmendmentValidatorTest {

    private final FrontmatterCodec frontmatterCodec = new FrontmatterCodec();
    private final TestFactoryArtifactAmendmentValidator validator =
            new TestFactoryArtifactAmendmentValidator(frontmatterCodec, new ScriptBundleCodec(frontmatterCodec));

    @Test
    void acceptsAmendmentThatKeepsFrontmatterIntact() {
        String amended = frontmatterCodec.render(Map.of("issue_key", "ERM-1"), "# Amended plan");

        assertThatCode(() -> validator.validate("test_plan.md", amended))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope_manifest.md", "test_plan.md", "test_results.md"})
    void rejectsAmendmentThatBreaksFrontmatter(String artifactName) {
        assertThatThrownBy(() -> validator.validate(artifactName, "reviewer deleted the frontmatter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amended '" + artifactName + "' is not structurally valid")
                .hasMessageContaining("must start with ---")
                .cause().isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void rejectsScriptBundleAmendmentWithoutFileSections() {
        String amended = frontmatterCodec.render(Map.of("framework", "karate"), "all file sections removed");

        assertThatThrownBy(() -> validator.validate("test_scripts.md", amended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no '## file:' sections")
                .cause().isInstanceOf(ArtifactFormatException.class);
    }

    @Test
    void ignoresArtifactsWithoutAStructuralContract() {
        assertThatCode(() -> validator.validate("sync_report.md", "free-form garbage %% not yaml"))
                .doesNotThrowAnyException();
    }
}
