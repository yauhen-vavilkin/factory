package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.folio.factory.devfactory.profile.TrustedProfileCatalog;
import org.folio.factory.devfactory.resolution.RepositoryAccess;
import org.folio.factory.devfactory.resolution.RepositoryAccessException;
import org.folio.factory.devfactory.resolution.RepositoryCatalog;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FileInboxTriggerTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final byte[] POM = "<project><properties><java.version>21</java.version></properties></project>"
      .getBytes(StandardCharsets.UTF_8);

  @TempDir Path inbox;
  private PipelineRouter router;
  private FakeAccess access;
  private FileInboxTrigger trigger;

  @BeforeEach
  void setUp() {
    router = org.mockito.Mockito.mock(PipelineRouter.class);
    access = new FakeAccess();
    trigger = trigger(access);
  }

  @Test
  void resolvedFileRoutesExactRevisionAndPreservedTextThenWritesReceipt() throws IOException {
    String task = valid("default", "repository: folio-org/folio-module-sidecar\n");
    write("task.yaml", task);
    UUID id = UUID.randomUUID();
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(id));

    trigger.poll();

    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), any());
    JsonNode payload = event.getValue().payload();
    assertThat(payload.path("baseRevision").asString()).isEqualTo(SHA);
    assertThat(payload.path("baseBranch").asString()).isEqualTo(SHA);
    assertThat(payload.path("rawTaskText").asString()).isEqualTo(task);
    assertThat(payload.path("resolvedIntent").path("executionReady").asBoolean()).isFalse();
    assertThat(inbox.resolve("processed/task.yaml")).exists();
    JsonNode receipt = new JsonMapper().readTree(
        Files.readAllBytes(inbox.resolve("processed/task.yaml.receipt.json")));
    assertThat(receipt.path("executionId").asString()).isEqualTo(id.toString());
    assertThat(receipt.path("status").asString()).isEqualTo("ADMITTED");
  }

  @Test
  void reorderedKeysAndRenamedFilesReplaySameSemanticAdmission() throws IOException {
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
    write("one.yaml", valid("same-run", "repository: folio-org/folio-module-sidecar\n"));
    write("renamed.yml", """
        goal: Implement requested behavior
        constraints: {}
        acceptanceCriteria:
          - text: It works
            source: TICKET
            id: AC-1
        metadata: {}
        deliveryMode: LOCAL_ONLY
        runKey: same-run
        baseRevision: %s
        repository: folio-org/folio-module-sidecar
        source: {project: MODSIDECAR, id: MODSIDECAR-208, type: JIRA}
        schemaVersion: 1
        """.formatted(SHA));

    trigger.poll();

    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(router, times(2)).routeAdmitted(any(), keys.capture());
    assertThat(keys.getAllValues()).hasSize(2);
    assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
  }

  @Test
  void explicitNewRunKeyCreatesDifferentAdmissionIdentity() throws IOException {
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
    write("one.yaml", valid("experiment-1", "repository: folio-org/folio-module-sidecar\n"));
    write("two.yaml", valid("experiment-2", "repository: folio-org/folio-module-sidecar\n"));
    trigger.poll();
    ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
    verify(router, times(2)).routeAdmitted(any(), keys.capture());
    assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
  }

  @Test
  void invalidOversizedAndSymlinkInputsProduceRejectReceiptsWithoutRouting() throws IOException {
    write("invalid.yaml", "schemaVersion: 1\nunknown: true\n");
    Files.write(inbox.resolve("oversized.yaml"), new byte[InboxTaskFileParser.MAX_BYTES + 1]);
    Path target = write("target.txt", valid("default", "repository: folio-org/folio-module-sidecar\n"));
    Files.createSymbolicLink(inbox.resolve("link.yaml"), target);

    trigger.poll();

    verify(router, never()).routeAdmitted(any(), any());
    for (String name : List.of("invalid.yaml", "oversized.yaml", "link.yaml")) {
      assertThat(inbox.resolve("failed/" + name)).exists();
      assertThat(inbox.resolve("failed/" + name + ".receipt.json")).exists();
    }
  }

  @Test
  void partialSubmissionIsIgnoredUntilFinalRename() throws IOException {
    Path partial = write("task.yaml.partial", valid("default",
        "repository: folio-org/folio-module-sidecar\n"));
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
    trigger.poll();
    verify(router, never()).routeAdmitted(any(), any());
    Files.move(partial, inbox.resolve("task.yaml"));
    trigger.poll();
    verify(router).routeAdmitted(any(), any());
  }

  @Test
  void conflictingRepositoryEvidenceIsAdmittedOnlyThroughTheDecisionEvent() throws IOException {
    UUID id = UUID.randomUUID();
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(id));
    write("conflict.yaml", valid("default", "repository: folio-org/mgr-tenant-entitlements\n"));

    trigger.poll();

    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), any());
    assertThat(event.getValue().type()).isEqualTo("file.inbox" + FileInboxTrigger.DECISION_EVENT_SUFFIX);
    assertThat(event.getValue().payload().path("resolvedIntent").path("decision").path("options")).hasSize(2);
    assertThat(event.getValue().payload().path("repoUrl").isNull()).isTrue();
    JsonNode receipt = new JsonMapper().readTree(
        Files.readAllBytes(inbox.resolve("processed/conflict.yaml.receipt.json")));
    assertThat(receipt.path("status").asString()).isEqualTo("ADMITTED");
    assertThat(receipt.path("code").asString()).isEqualTo("ADMITTED_NEEDS_DECISION");
    assertThat(receipt.path("executionId").asString()).isEqualTo(id.toString());
  }

  @Test
  void decisionTaskWithoutDecisionFlowKeepsStructuredBlockedReceipt() throws IOException {
    when(router.routeAdmitted(any(), any())).thenReturn(List.of());
    write("conflict.yaml", valid("default", "repository: folio-org/mgr-tenant-entitlements\n"));

    trigger.poll();

    JsonNode receipt = new JsonMapper().readTree(
        Files.readAllBytes(inbox.resolve("blocked/conflict.yaml.receipt.json")));
    assertThat(receipt.path("code").asString()).isEqualTo("REPOSITORY_SELECTION");
    assertThat(receipt.path("resolution").path("repository").path("candidates")).hasSize(2);
  }

  @Test
  void resolvedTaskNeverUsesTheDecisionEvent() throws IOException {
    when(router.routeAdmitted(any(), any())).thenReturn(List.of(UUID.randomUUID()));
    write("task.yaml", valid("default", "repository: folio-org/folio-module-sidecar\n"));

    trigger.poll();

    ArgumentCaptor<TriggerEvent> event = ArgumentCaptor.forClass(TriggerEvent.class);
    verify(router).routeAdmitted(event.capture(), any());
    assertThat(event.getValue().type()).isEqualTo("file.inbox");
  }

  @Test
  void transientRepositoryOrDatabaseFailureLeavesValidInputRetryable() throws IOException {
    access.failure = new RepositoryAccessException("temporary lookup failure");
    Path lookup = write("lookup.yaml", valid("default", "repository: folio-org/folio-module-sidecar\n"));
    trigger.poll();
    assertThat(lookup).exists();
    access.failure = null;
    when(router.routeAdmitted(any(), any())).thenThrow(new TransientDataAccessResourceException("db down"));
    trigger.poll();
    assertThat(lookup).exists();
    assertThat(inbox.resolve("failed/lookup.yaml.receipt.json")).doesNotExist();
  }

  @Test
  void unsupportedNodeIsBlockedBeforeRouting() throws IOException {
    access.files.clear();
    access.files.put("package.json", "{\"engines\":{\"node\":\"22\"}}".getBytes(StandardCharsets.UTF_8));
    write("node.yaml", valid("default", "repository: folio-org/folio-module-sidecar\n"));
    trigger.poll();
    verify(router, never()).routeAdmitted(any(), any());
    assertThat(Files.readString(inbox.resolve("blocked/node.yaml.receipt.json")))
        .contains("UNSUPPORTED_PROFILE");
  }

  private FileInboxTrigger trigger(RepositoryAccess repositoryAccess) {
    TaskResolutionService resolver = new TaskResolutionService(new RepositoryCatalog(), repositoryAccess,
        new TrustedProfileCatalog());
    return new FileInboxTrigger(new InboxProperties(true, inbox, 5000L),
        new InboxTaskFileParser(), resolver, router);
  }

  private static String valid(String runKey, String repositoryLine) {
    return """
        schemaVersion: 1
        source:
          type: JIRA
          id: MODSIDECAR-208
          project: MODSIDECAR
        %sbaseRevision: %s
        runKey: %s
        deliveryMode: LOCAL_ONLY
        metadata: {}
        goal: Implement requested behavior
        acceptanceCriteria:
          - id: AC-1
            text: It works
            source: TICKET
        constraints: {}
        """.formatted(repositoryLine, SHA, runKey);
  }

  private Path write(String name, String content) throws IOException {
    Path file = inbox.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static final class FakeAccess implements RepositoryAccess {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private RuntimeException failure;

    private FakeAccess() {
      files.put("pom.xml", POM);
    }

    @Override public String resolveBranch(String slug, String branch) {
      if (failure != null) throw failure;
      return SHA;
    }
    @Override public String verifyCommit(String slug, String sha) {
      if (failure != null) throw failure;
      return sha;
    }
    @Override public Optional<byte[]> readFile(String slug, String sha, String path, int maxBytes) {
      if (failure != null) throw failure;
      return Optional.ofNullable(files.get(path));
    }
  }
}
