package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class InboxTaskFileParserTest {
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private final InboxTaskFileParser parser = new InboxTaskFileParser();

  @Test
  void parsesV1AndPreservesExactRawText(@TempDir Path dir) throws IOException {
    String yaml = v1("baseRef: master");
    Path file = write(dir, "task.yaml", yaml);

    TaskRequest task = parser.parse(file);

    assertThat(task.schemaVersion()).isEqualTo(1);
    assertThat(task.source().id()).isEqualTo("MODSIDECAR-208");
    assertThat(task.repository()).isEqualTo("folio-org/folio-module-sidecar");
    assertThat(task.baseRef()).isEqualTo("master");
    assertThat(task.baseRevision()).isNull();
    assertThat(task.acceptanceCriteria()).extracting(TaskRequest.AcceptanceCriterion::id)
        .containsExactly("AC-1");
    assertThat(task.rawTaskText()).isEqualTo(yaml);
    assertThat(task.legacyAdapted()).isFalse();
  }

  @Test
  void publishedSchemaIsVersionedStrictAndMatchesParserBoundary() throws IOException {
    try (var input = getClass().getResourceAsStream("/schemas/task-request-v1.schema.json")) {
      assertThat(input).isNotNull();
      var schema = JsonMapper.builder().build().readTree(input.readAllBytes());
      assertThat(schema.path("$id").asString()).isEqualTo("urn:folio-factory:TaskRequest:v1");
      assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
      assertThat(schema.path("properties").path("schemaVersion").path("const").asInt())
          .isEqualTo(TaskRequest.CURRENT_SCHEMA_VERSION);
    }
  }

  @Test
  void adaptsLegacyEightKeyFileWithoutDefaultingBase(@TempDir Path dir) throws IOException {
    String yaml = """
        id: MODSIDECAR-208
        repo: folio-org/folio-module-sidecar
        base: %s
        branch: task/MODSIDECAR-208
        goal: Add the setting.
        acceptance: [works]
        constraints: {}
        notes: keep this
        """.formatted(SHA);

    TaskRequest task = parser.parse(write(dir, "legacy.yml", yaml));

    assertThat(task.legacyAdapted()).isTrue();
    assertThat(task.baseRevision()).isEqualTo(SHA);
    assertThat(task.metadata().path("legacyDeliveryBranch").asString())
        .isEqualTo("task/MODSIDECAR-208");
    assertThat(task.acceptanceCriteria().getFirst().id()).isEqualTo("LEGACY-AC-1");
    assertThat(task.rawTaskText()).isEqualTo(yaml);
  }

  @Test
  void legacyMissingBaseIsRejectedInsteadOfGuessingMain(@TempDir Path dir) throws IOException {
    assertThatThrownBy(() -> parser.parse(write(dir, "legacy.yaml", """
        id: X-1
        repo: folio-org/mod-scheduler
        goal: Fix it.
        """))).isInstanceOf(InboxTaskFileException.class).hasMessageContaining("base");
  }

  @Test
  void duplicateAndUnknownKeysAreRejected(@TempDir Path dir) throws IOException {
    assertThatThrownBy(() -> parser.parse(write(dir, "duplicate.yaml",
        v1("baseRef: main") + "goal: second\n")))
        .isInstanceOf(InboxTaskFileException.class).hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> parser.parse(write(dir, "unknown.yaml",
        v1("baseRef: main") + "shell: mvn test\n")))
        .isInstanceOf(InboxTaskFileException.class).hasMessageContaining("shell");
  }

  @Test
  void revisionAndRefAreSeparateAndExactlyOneIsRequired(@TempDir Path dir) throws IOException {
    assertThat(parser.parse(write(dir, "sha.json", v1("baseRevision: " + SHA))).baseRevision())
        .isEqualTo(SHA);
    assertThatThrownBy(() -> parser.parse(write(dir, "both.yaml",
        v1("baseRevision: " + SHA + "\nbaseRef: main"))))
        .hasMessageContaining("exactly one");
    assertThatThrownBy(() -> parser.parse(write(dir, "short.yaml", v1("baseRevision: abc123"))))
        .hasMessageContaining("full 40-character");
    assertThatThrownBy(() -> parser.parse(write(dir, "sha-ref.yaml", v1("baseRef: " + SHA))))
        .hasMessageContaining("not a commit SHA");
  }

  @Test
  void taskCannotSupplyCommandsImagesMountsCredentialsOrNetwork(@TempDir Path dir) throws IOException {
    for (String key : new String[] {"commands", "image", "mounts", "credentials", "networkPolicy"}) {
      String content = v1("baseRef: main").replace("constraints: {}", "constraints:\n  " + key + ": bad");
      assertThatThrownBy(() -> parser.parse(write(dir, key + ".yaml", content)))
          .hasMessageContaining("cannot define trusted configuration");
    }
  }

  @Test
  void oversizedInvalidUtf8AndSymlinkAreRejected(@TempDir Path dir) throws IOException {
    Path oversized = dir.resolve("oversized.yaml");
    Files.write(oversized, new byte[InboxTaskFileParser.MAX_BYTES + 1]);
    assertThatThrownBy(() -> parser.parse(oversized)).hasMessageContaining("256 KiB");
    assertThatThrownBy(() -> parser.parseBytes(new byte[] {(byte) 0xc3, 0x28}, "bad"))
        .hasMessageContaining("UTF-8");
    Path target = write(dir, "target.yaml", v1("baseRef: main"));
    Path link = dir.resolve("link.yaml");
    Files.createSymbolicLink(link, target);
    assertThatThrownBy(() -> parser.parse(link)).hasMessageContaining("symbolic links");
  }

  @Test
  void hostileYamlTagIsRejected() {
    assertThatThrownBy(() -> parser.parseBytes("!!javax.script.ScriptEngineManager []"
        .getBytes(StandardCharsets.UTF_8), "hostile.yaml"))
        .isInstanceOf(InboxTaskFileException.class);
    assertThatThrownBy(() -> parser.parseBytes((v1("baseRef: main") + "---\nfoo: bar\n")
        .getBytes(StandardCharsets.UTF_8), "multi.yaml"))
        .isInstanceOf(InboxTaskFileException.class).hasMessageContaining("Trailing token");
  }

  @Test
  void declaredDecisionIsParsedStrictlyAndEngineeringChoicesAreNotADecisionCategory(@TempDir Path dir)
      throws IOException {
    String decision = """
        decisions:
          - id: limit
            category: PRODUCT_SEMANTICS
            question: Raise the limit or remove it?
            whyItMatters: External API contract
            options:
              - {id: raise, label: Raise to 100, consequence: Bounded}
              - {id: remove, label: Remove the limit, consequence: Unbounded}
            evidence:
              - {path: src/schema.json, terms: [maxItems]}
        """;
    TaskRequest task = parser.parse(write(dir, "decision.yaml", v1("baseRef: master") + decision));
    assertThat(task.decisions()).singleElement().satisfies(declared -> {
      assertThat(declared.options()).extracting(TaskRequest.Option::id).containsExactly("raise", "remove");
      assertThat(declared.recommendedOptionId()).isNull();
      assertThat(declared.evidence()).singleElement().extracting(TaskRequest.Evidence::path)
          .isEqualTo("src/schema.json");
    });

    assertThatThrownBy(() -> parser.parse(write(dir, "debug.yaml", v1("baseRef: master")
        + decision.replace("PRODUCT_SEMANTICS", "IMPLEMENTATION_DEBUGGING"))))
        .hasMessageContaining("decision category");
    assertThatThrownBy(() -> parser.parse(write(dir, "one-option.yaml", v1("baseRef: master")
        + decision.replace("      - {id: remove, label: Remove the limit, consequence: Unbounded}\n", ""))))
        .hasMessageContaining("between 2 and 5 options");
    assertThatThrownBy(() -> parser.parse(write(dir, "bad-recommendation.yaml", v1("baseRef: master")
        + decision.replace("    options:", "    recommendedOptionId: other\n    recommendationRationale: why\n    options:"))))
        .hasMessageContaining("recommendedOptionId");
    assertThatThrownBy(() -> parser.parse(write(dir, "unsafe.yaml", v1("baseRef: master")
        + decision.replace("src/schema.json", "../secrets"))))
        .hasMessageContaining("unsafe");
  }

  private static String v1(String revision) {
    return """
        schemaVersion: 1
        source:
          type: JIRA
          id: MODSIDECAR-208
          project: MODSIDECAR
          component: folio-module-sidecar
        repository: folio-org/folio-module-sidecar
        %s
        runKey: default
        deliveryMode: LOCAL_ONLY
        metadata: {}
        goal: Add the setting.
        acceptanceCriteria:
          - id: AC-1
            text: The setting is present.
            source: TICKET
        constraints: {}
        notes: preserve me
        """.formatted(revision);
  }

  private static Path write(Path dir, String name, String value) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, value, StandardCharsets.UTF_8);
    return file;
  }
}
