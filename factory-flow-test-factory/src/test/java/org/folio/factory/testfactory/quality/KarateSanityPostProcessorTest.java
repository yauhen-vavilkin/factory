package org.folio.factory.testfactory.quality;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KarateSanityPostProcessorTest {

    private final ScriptBundleCodec bundleCodec = new ScriptBundleCodec(new FrontmatterCodec());
    private final KarateSanityPostProcessor processor = new KarateSanityPostProcessor(bundleCodec);

    private String bundle(String framework, String path, String content) {
        return bundleCodec.render(new ScriptBundle(framework, List.of(
                new ScriptBundle.ScriptFile(path, List.of("TC-01"), content))));
    }

    @Test
    void ignoresStepsThatDoNotProduceAScriptBundle() {
        assertThatCode(() -> processor.process(null, null,
                Map.of("test_plan.md", "not a bundle at all")))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsNonKarateFrameworksEvenWithNonFeatureFiles() {
        String content = bundle("restassured", "src/test/java/ATest.java", "public class ATest {}");

        assertThatCode(() -> processor.process(null, null, Map.of("test_scripts.md", content)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsKarateBundleContainingNonFeatureFile() {
        String content = bundle("karate", "features/notes.txt", "Feature: F\n  Scenario: s");

        assertThatThrownBy(() -> processor.process(null, null, Map.of("test_scripts.md", content)))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("features/notes.txt")
                .hasMessageContaining("is not a .feature file");
    }

    @Test
    void rejectsFeatureFileWithoutFeatureAndScenarioStructure() {
        String content = bundle("karate", "features/broken.feature", "just some prose, no keywords");

        assertThatThrownBy(() -> processor.process(null, null, Map.of("test_scripts.md", content)))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("features/broken.feature")
                .hasMessageContaining("missing Feature/Scenario structure");
    }

    @Test
    void acceptsWellFormedKarateBundle() {
        String content = bundle("karate", "features/ok.feature",
                "Feature: Agreements\n\n  Scenario: TC-01 create agreement\n    * status 201");

        assertThatCode(() -> processor.process(null, null, Map.of("test_scripts.md", content)))
                .doesNotThrowAnyException();
    }
}
