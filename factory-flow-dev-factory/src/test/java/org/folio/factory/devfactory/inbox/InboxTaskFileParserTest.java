package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboxTaskFileParserTest {

    private static final String HEX_40 = "0123456789abcdef0123456789abcdef01234567";
    private static final String HEX_64 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final InboxTaskFileParser parser = new InboxTaskFileParser();

    @Test
    void fullEightFieldFileParses(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-001
                repo: https://github.com/acme/widgets.git
                base: release/1.0
                branch: task/TASK-001
                goal: |
                  Implement the widget parser.
                  Keep it streaming.
                acceptance:
                  - parser handles empty input
                  - all tests stay green
                constraints:
                  allow_paths:
                    - src/main/java/
                    - src/test/java/
                  max_diff_lines: 500
                notes: |
                  First line of notes.
                  Second line of notes.
                """);

        InboxTask task = parser.parse(file);

        assertThat(task.id()).isEqualTo("TASK-001");
        assertThat(task.repo()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(task.base()).isEqualTo("release/1.0");
        assertThat(task.branch()).isEqualTo("task/TASK-001");
        assertThat(task.goal()).isEqualTo("Implement the widget parser.\nKeep it streaming.\n");
        assertThat(task.acceptance()).containsExactly(
                "parser handles empty input", "all tests stay green");
        assertThat(task.constraints()).containsOnlyKeys("allow_paths", "max_diff_lines");
        assertThat(task.constraints().get("allow_paths"))
                .isEqualTo(List.of("src/main/java/", "src/test/java/"));
        assertThat(task.constraints().get("max_diff_lines")).isEqualTo(500);
        assertThat(task.notes()).isEqualTo("First line of notes.\nSecond line of notes.\n");
    }

    @Test
    void minimalFileGetsDefaults(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-002
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                """);

        InboxTask task = parser.parse(file);

        assertThat(task.id()).isEqualTo("TASK-002");
        assertThat(task.repo()).isEqualTo("https://github.com/acme/gadgets.git");
        assertThat(task.goal()).isEqualTo("Make the gadget compile.");
        assertThat(task.base()).isEqualTo("main");
        assertThat(task.branch()).isEqualTo("task/TASK-002");
        assertThat(task.acceptance()).isEmpty();
        assertThat(task.constraints()).isEmpty();
        assertThat(task.notes()).isNull();
    }

    @Test
    void malformedYamlRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, "id: [unclosed");

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class);
    }

    @Test
    void missingGoalRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-003
                repo: https://github.com/acme/gadgets.git
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void blankIdRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: "   "
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("id");
    }

    @Test
    void unknownTopLevelKeyRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-004
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                goals: stray key
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("goals");
    }

    @Test
    void explicitHex40BaseRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-005
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                base: %s
                """.formatted(HEX_40));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("base");
    }

    @Test
    void explicitHex64BranchRejected(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: TASK-006
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                branch: %s
                """.formatted(HEX_64));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("branch");
    }

    @Test
    void idWithSpaceRejectedViaDefaultBranch(@TempDir Path tmp) throws IOException {
        Path file = write(tmp, """
                id: "bad id"
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InboxTaskFileException.class)
                .hasMessageContaining("branch");
    }

    private static Path write(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("task.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }
}
