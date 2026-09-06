package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

class FileInboxTriggerTest {

    private static final String HEX_40 = "0123456789abcdef0123456789abcdef01234567";

    private static final String VALID_TASK = """
            id: TASK-100
            repo: https://github.com/acme/widgets.git
            goal: Implement the widget parser.
            """;

    @TempDir
    Path inbox;

    private PipelineRouter router;
    private FileInboxTrigger trigger;

    @BeforeEach
    void setUp() {
        router = org.mockito.Mockito.mock(PipelineRouter.class);
        trigger = new FileInboxTrigger(
                new InboxProperties(true, inbox, 5000L), new InboxTaskFileParser(), router);
    }

    @Test
    void validFileRoutesNormalizedPayloadAndClaims() throws IOException {
        Path file = write("t1.yaml", VALID_TASK);
        when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));

        trigger.poll();

        ArgumentCaptor<TriggerEvent> captor = ArgumentCaptor.forClass(TriggerEvent.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(router, times(1)).routeAdmitted(captor.capture(), keyCaptor.capture());
        TriggerEvent event = captor.getValue();
        assertThat(event.type()).isEqualTo("file.inbox");
        assertThat(event.source()).isEqualTo("file-inbox:t1.yaml");
        JsonNode payload = event.payload();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : payload.properties()) {
            keys.add(field.getKey());
        }
        assertThat(keys).containsExactlyInAnyOrder(
                "taskId", "repoUrl", "baseBranch", "branch", "goal",
                "acceptance", "constraints", "notes");
        assertThat(payload.get("taskId").asString()).isEqualTo("TASK-100");
        assertThat(payload.get("repoUrl").asString()).isEqualTo("https://github.com/acme/widgets.git");
        assertThat(payload.get("baseBranch").asString()).isEqualTo("main");
        assertThat(payload.get("branch").asString()).isEqualTo("task/TASK-100");
        assertThat(payload.get("goal").asString()).isEqualTo("Implement the widget parser.");
        assertThat(payload.get("acceptance").isArray()).isTrue();
        assertThat(payload.get("acceptance").size()).isZero();
        assertThat(payload.get("constraints").isObject()).isTrue();
        assertThat(payload.get("constraints").size()).isZero();
        assertThat(payload.get("notes").isNull()).isTrue();
        assertThat(keyCaptor.getValue())
                .startsWith("file.inbox:")
                .hasSize("file.inbox:".length() + 64);
        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("processed/t1.yaml")).exists();
    }

    @Test
    void malformedYamlMovesToFailedAndStaysObservable() throws IOException {
        Path file = write("bad.yaml", "id: [unclosed");

        trigger.poll();

        assertThat(file).doesNotExist();
        Path failedFile = inbox.resolve("failed/bad.yaml");
        assertThat(failedFile).exists();
        assertThat(Files.readString(failedFile)).isEqualTo("id: [unclosed");
        verify(router, never()).routeAdmitted(any(), any());
    }

    @Test
    void missingGoalMovesToFailed() throws IOException {
        Path file = write("nogoal.yaml", """
                id: TASK-101
                repo: https://github.com/acme/gadgets.git
                """);

        trigger.poll();

        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("failed/nogoal.yaml")).exists();
        verify(router, never()).routeAdmitted(any(), any());
    }

    @Test
    void rawShaBaseMovesToFailed() throws IOException {
        Path file = write("shabase.yaml", """
                id: TASK-102
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                base: %s
                """.formatted(HEX_40));

        trigger.poll();

        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("failed/shabase.yaml")).exists();
        verify(router, never()).routeAdmitted(any(), any());
    }

    @Test
    void invalidRefnameBranchMovesToFailed() throws IOException {
        Path file = write("badbranch.yaml", """
                id: TASK-103
                repo: https://github.com/acme/gadgets.git
                goal: Make the gadget compile.
                branch: bad..name
                """);

        trigger.poll();

        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("failed/badbranch.yaml")).exists();
        verify(router, never()).routeAdmitted(any(), any());
    }

    @Test
    void emptyRouteResultMovesToFailed() throws IOException {
        Path file = write("nomatch.yaml", VALID_TASK);
        when(router.routeAdmitted(any(), any())).thenReturn(List.of());

        trigger.poll();

        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("failed/nomatch.yaml")).exists();
        verify(router, times(1)).routeAdmitted(any(), any());
    }

    @Test
    void routeThrowMovesToFailedAndDoesNotPropagate() throws IOException {
        Path file = write("boom.yaml", VALID_TASK);
        when(router.routeAdmitted(any(), any())).thenThrow(new RuntimeException("router down"));

        assertThatCode(trigger::poll).doesNotThrowAnyException();

        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("failed/boom.yaml")).exists();
    }

    @Test
    void secondPollClaimsNothingNew() throws IOException {
        write("t8.yaml", VALID_TASK);
        when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
        trigger.poll();
        verify(router, times(1)).routeAdmitted(any(), any());
        assertThat(inbox.resolve("processed/t8.yaml")).exists();

        write("processed/inside.yaml", VALID_TASK);
        write("notes.txt", "not a task file");

        trigger.poll();

        verify(router, times(1)).routeAdmitted(any(), any());
        assertThat(inbox.resolve("processed/inside.yaml")).exists();
        assertThat(inbox.resolve("notes.txt")).exists();
        try (DirectoryStream<Path> top = Files.newDirectoryStream(inbox, "*.{yaml,yml}")) {
            List<Path> remaining = new ArrayList<>();
            top.forEach(remaining::add);
            assertThat(remaining).isEmpty();
        }
    }

    @Test
    void nonexistentInboxDirIsSilentNoOp(@TempDir Path tmp) {
        FileInboxTrigger missing = new FileInboxTrigger(
                new InboxProperties(true, tmp.resolve("does-not-exist"), 5000L),
                new InboxTaskFileParser(), router);

        assertThatCode(missing::poll).doesNotThrowAnyException();

        verifyNoInteractions(router);
    }

    /**
     * T22 R3 crash boundary: the route happens before the claim move, so a
     * failed move (or a crash between commit and move) must leave the file in
     * the inbox and re-polling must re-route it as the SAME admission — the
     * router's idempotent admission then returns the existing execution
     * instead of creating a duplicate.
     */
    @Test
    void failedClaimLeavesFileAndRePollReplaysSameAdmissionKey() throws IOException {
        Path file = write("crash.yaml", VALID_TASK);
        when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
        // Break the claim move: 'processed' exists as a regular file, so
        // createDirectories(targetDir) fails inside claim().
        Files.writeString(inbox.resolve("processed"), "not a directory");

        trigger.poll();
        verify(router, times(1)).routeAdmitted(any(), any());
        assertThat(file).as("file must stay in the inbox when the claim move fails").exists();

        Files.delete(inbox.resolve("processed"));
        trigger.poll();
        verify(router, times(2)).routeAdmitted(any(), any());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(router, times(2)).routeAdmitted(any(), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues()).as("both routes must carry the same admission key")
                .containsExactly(keyCaptor.getAllValues().getFirst(), keyCaptor.getAllValues().getFirst());
        assertThat(file).doesNotExist();
        assertThat(inbox.resolve("processed/crash.yaml")).exists();
    }

    /**
     * T22 R1: admission identity is the normalized task content, not the file
     * name — and cosmetic-only YAML differences (key order, indentation,
     * comments) that parse to the same normalized task are the same admission.
     */
    @Test
    void admissionKeyIsContentIdentityRegardlessOfFileNameOrYamlCosmetics() throws IOException {
        when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
        String reordered = """
                # operator comment
                goal:   Implement the widget parser.
                repo:   https://github.com/acme/widgets.git
                id: TASK-100
                """;

        write("t1.yaml", VALID_TASK);
        write("a-completely-different-name.yml", reordered);
        trigger.poll();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(router, times(2)).routeAdmitted(any(), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0))
                .as("same normalized content under different names is the same admission")
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }

    /**
     * T22 R1/R5: a re-dropped file with changed content is an intentional new
     * revision — a distinct admission key, so the router admits a new
     * execution with its own audit trail.
     */
    @Test
    void revisedContentUnderTheSameFileNameIsADistinctAdmissionKey() throws IOException {
        when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));

        write("t9.yaml", VALID_TASK);
        trigger.poll();
        write("t9.yaml", VALID_TASK.replace("Implement", "Rewrite and harden"));
        trigger.poll();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(router, times(2)).routeAdmitted(any(), keyCaptor.capture());
        assertThat(keyCaptor.getAllValues().get(0))
                .isNotEqualTo(keyCaptor.getAllValues().get(1));
    }

    private Path write(String name, String content) throws IOException {
        Path file = inbox.resolve(name);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
