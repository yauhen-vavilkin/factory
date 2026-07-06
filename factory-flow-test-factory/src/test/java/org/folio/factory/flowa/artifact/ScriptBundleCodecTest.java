package org.folio.factory.flowa.artifact;

import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.flowa.model.ScriptBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptBundleCodecTest {

    private final ScriptBundleCodec codec = new ScriptBundleCodec(new FrontmatterCodec());

    @Test
    void roundTripsBundleWithMultipleFiles() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/agreements.feature", List.of("TC-01", "TC-02"),
                        "Feature: Agreements\n\n  Scenario: TC-01 create agreement\n    * url 'http://x'"),
                new ScriptBundle.ScriptFile("features/errors.feature", List.of("TC-03"),
                        "Feature: Errors\n\n  Scenario: TC-03 missing name\n    * status 422")));

        String rendered = codec.render(bundle);
        ScriptBundle parsed = codec.parse(rendered);

        assertThat(parsed.framework()).isEqualTo("karate");
        assertThat(parsed.files()).hasSize(2);
        assertThat(parsed.files().getFirst().path()).isEqualTo("features/agreements.feature");
        assertThat(parsed.files().getFirst().caseIds()).containsExactly("TC-01", "TC-02");
        assertThat(parsed.files().getFirst().content()).contains("Scenario: TC-01 create agreement");
        assertThat(parsed.files().get(1).content()).contains("status 422");
    }

    @Test
    void preservesTripleBacktickContentInsideFiles() {
        ScriptBundle bundle = new ScriptBundle("karate", List.of(
                new ScriptBundle.ScriptFile("features/doc.feature", List.of("TC-01"),
                        "Feature: F\n  Scenario: TC-01 s\n    # embedded ``` fence in a comment")));
        ScriptBundle parsed = codec.parse(codec.render(bundle));
        assertThat(parsed.files().getFirst().content()).contains("embedded ``` fence");
    }

    @Test
    void rejectsBundleWithoutFileSections() {
        assertThatThrownBy(() -> codec.parse("---\nframework: karate\nfiles: []\n---\n\nno sections"))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("no '## file:' sections");
    }
}
