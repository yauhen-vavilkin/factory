package org.folio.factory.app.web;

import org.folio.factory.app.developer.CodingTranscriptStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperTranscriptViewTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant AT = Instant.parse("2026-11-10T21:00:00Z");

    @Test
    void sectionsAreNamedAsTheRunPageNamesTheWork() {
        var rounds = List.of(new FeedbackRows.RoundStart("Round 1", AT.plusSeconds(600)),
                new FeedbackRows.RoundStart("Round 2", AT.plusSeconds(1200)));
        var entries = List.of(
                note("implement", 0), note("continue-implementation", 300),
                note("pr-follow-up", 660), note("pr-follow-up", 900), note("pr-follow-up", 1260));

        assertThat(DeveloperTranscriptView.sections(entries, rounds))
                .extracting(DeveloperTranscriptView.Section::label)
                .containsExactly("Implementation", "Implementation, after your answer", "Round 1 · PR feedback",
                        "Round 1 · PR feedback, continued", "Round 2 · PR feedback");
    }

    @Test
    void aMarkdownHeadingOfTheAgentIsABoldLineButACommentInCodeStaysCode() {
        var text = "I checked the patterns.\n\n## Regexes\nThey match.\n```bash\n# run the tests\nmvn test\n```";

        var row = DeveloperTranscriptView.sections(List.of(entry("implement", 0, "text", text)), List.of())
                .getFirst().entries().getFirst();

        assertThat(row.blocks()).containsExactly(
                new DeveloperTranscriptView.Block("I checked the patterns.", false),
                new DeveloperTranscriptView.Block("Regexes", true),
                new DeveloperTranscriptView.Block("They match.\n```bash\n# run the tests\nmvn test\n```", false));
    }

    private static CodingTranscriptStore.Entry note(String step, int seconds) {
        return entry(step, seconds, "note", "note");
    }

    private static CodingTranscriptStore.Entry entry(String step, int seconds, String kind, String text) {
        var detail = JSON.createObjectNode().put("text", text);
        return new CodingTranscriptStore.Entry(UUID.randomUUID(), 0, step, kind, AT.plusSeconds(seconds), detail);
    }
}
