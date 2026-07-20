package org.folio.factory.agents.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCatalogTest {

    private final PromptCatalog catalog = newScannedCatalog();

    @Test
    void findsTestWorkerPromptsOnClasspath() {
        assertThat(catalog.exists("test-worker", "system")).isTrue();
        assertThat(catalog.exists("test-worker", "user")).isTrue();
    }

    @Test
    void promptsForReturnsEveryPromptOfAWorker() {
        assertThat(catalog.promptsFor("test-worker")).containsExactly("system", "user");
    }

    @Test
    void unknownWorkerHasNoPrompts() {
        assertThat(catalog.exists("no-such-worker", "system")).isFalse();
        assertThat(catalog.promptsFor("no-such-worker")).isEmpty();
    }

    @Test
    void allContainsTheTestWorkerRefs() {
        assertThat(catalog.all())
                .contains(new PromptCatalog.PromptRef("test-worker", "system"),
                        new PromptCatalog.PromptRef("test-worker", "user"));
    }

    private static PromptCatalog newScannedCatalog() {
        PromptCatalog catalog = new PromptCatalog();
        catalog.scan();
        return catalog;
    }
}
