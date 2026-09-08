package org.folio.factory.devfactory.worker.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.folio.factory.core.engine.StepRecoveryStore;
import org.folio.factory.core.service.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * File-based recovery-bundle store for the dev-factory coding step (T25).
 *
 * <p>Each terminal attempt of a coding step publishes its produced artifact
 * bytes plus a {@code manifest.json} (per-file sha256 digests, completeness
 * state, failure reason) under
 * {@code <root>/<executionId>/<stepId>/attempt-<attempt>/}. The root must
 * live outside both the temp workDir and the sandbox workspace root — both of
 * those are destroyed (best-effort workDir delete on the default path, and
 * the sandbox service sweeps the owner-named workspace when that owner
 * creates again; owners are attempt-scoped, so a retry's create never sweeps
 * a prior attempt's workspace), so the bundle is the only cross-attempt
 * preserved copy of a run.
 *
 * <p><em>Bounded durability:</em> publication is a plain file write sequence
 * (artifacts first, manifest last, so a manifest's presence implies the
 * artifact writes completed). No fsync or force is performed and no
 * power-loss or crash-atomicity is claimed — the guarantee ends at "the
 * bytes reached the filesystem".
 */
public final class RecoveryBundleStore implements StepRecoveryStore {

    public static final String SCHEMA = "devfactory/recovery-v1";
    public static final String MANIFEST_FILE = "manifest.json";
    public static final Path DEFAULT_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "devfactory-recovery");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path root;

    public RecoveryBundleStore(Path root) {
        this.root = root == null ? DEFAULT_ROOT : root.toAbsolutePath();
    }

    /**
     * Publishes one attempt's bundle: each artifact under its own name plus
     * the digest manifest, written only into this attempt's own directory.
     * Sibling attempts are never touched, and an existing non-empty attempt
     * directory is never overwritten — a later attempt must not destroy a
     * prior attempt's preserved copy, so this fails loudly instead.
     *
     * @param complete      {@code true} for a run that produced all declared
     *                      outputs, {@code false} for a partial one
     * @param failureReason nullable machine-readable reason for incomplete
     *                      bundles (e.g. {@code EXPORT_FAILED},
     *                      {@code PERSIST_FAILED})
     * @return the published attempt directory (absolute)
     * @throws IllegalStateException if the attempt directory already holds a
     *                               published bundle
     * @throws UncheckedIOException  if the write fails
     */
    public Path publish(UUID executionId, String stepId, int attempt, String taskId,
            String baseRevision, Map<String, String> artifacts, boolean complete,
            String failureReason) {
        Path attemptDir = attemptPath(executionId, stepId, attempt);
        try {
            if (Files.isDirectory(attemptDir) && !isEmpty(attemptDir)) {
                throw new IllegalStateException("recovery bundle already published at "
                        + attemptDir + " — a later publish must not overwrite it");
            }
            Files.createDirectories(attemptDir);
            ObjectNode manifest = MAPPER.createObjectNode();
            manifest.put("schema", SCHEMA);
            manifest.put("execution_id", executionId.toString());
            manifest.put("step_id", stepId);
            manifest.put("attempt", attempt);
            if (taskId == null) {
                manifest.putNull("task_id");
            } else {
                manifest.put("task_id", taskId);
            }
            if (baseRevision == null) {
                manifest.putNull("base_revision");
            } else {
                manifest.put("base_revision", baseRevision);
            }
            manifest.put("created_at", Instant.now().toString());
            manifest.put("completeness", complete ? "COMPLETE" : "INCOMPLETE");
            if (failureReason == null) {
                manifest.putNull("failure_reason");
            } else {
                manifest.put("failure_reason", failureReason);
            }
            ArrayNode manifestArtifacts = manifest.putArray("artifacts");
            for (Map.Entry<String, String> artifact : artifacts.entrySet()) {
                String name = artifact.getKey();
                if (name == null || name.isBlank() || name.contains("/")
                        || name.contains("\\")) {
                    throw new IllegalArgumentException(
                            "artifact name is not a plain file name: '" + name + "'");
                }
                byte[] bytes = artifact.getValue().getBytes(StandardCharsets.UTF_8);
                Files.writeString(attemptDir.resolve(name), artifact.getValue());
                ObjectNode entry = manifestArtifacts.addObject();
                entry.put("name", name);
                entry.put("sha256", ArtifactStore.sha256(artifact.getValue()));
                entry.put("size_bytes", bytes.length);
            }
            Files.writeString(attemptDir.resolve(MANIFEST_FILE), manifest.toString());
            return attemptDir;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to publish recovery bundle to " + attemptDir, e);
        }
    }

    /**
     * Reads a published bundle back; empty unless this attempt's manifest
     * exists.
     */
    public Optional<RecoveryBundle> find(UUID executionId, String stepId, int attempt) {
        Path attemptDir = attemptPath(executionId, stepId, attempt);
        Path manifestFile = attemptDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RecoveryBundle(attemptDir,
                    MAPPER.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read recovery bundle at " + attemptDir, e);
        }
    }

    /**
     * Deletes exactly one attempt's bundle — the only destructive action this
     * store offers, scoped so an acknowledged attempt can never take a
     * sibling attempt's preserved copy with it. Absent bundles are a no-op.
     */
    public void discard(UUID executionId, String stepId, int attempt) {
        Path attemptDir = attemptPath(executionId, stepId, attempt);
        if (!Files.exists(attemptDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(attemptDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(path);
                } catch (NoSuchFileException e) {
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to discard recovery bundle at " + attemptDir, e);
        }
    }

    /**
     * T25 S02 core seam: the engine acknowledges an attempt (every declared
     * output durably persisted) by discarding exactly that attempt's bundle.
     */
    @Override
    public void discardAcknowledged(UUID executionId, String stepId, int attempt) {
        discard(executionId, stepId, attempt);
    }

    private Path attemptPath(UUID executionId, String stepId, int attempt) {
        return root.resolve(executionId.toString()).resolve(stepId).resolve("attempt-" + attempt);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /** A published bundle: its directory, its manifest, and typed views of both. */
    public record RecoveryBundle(Path directory, JsonNode manifest) {

        public String schema() {
            return manifest.path("schema").asString("");
        }

        public String completeness() {
            return manifest.path("completeness").asString("");
        }

        public String failureReason() {
            JsonNode reason = manifest.path("failure_reason");
            return reason.isMissingNode() || reason.isNull() ? null : reason.asString();
        }

        public List<String> artifactNames() {
            List<String> names = new ArrayList<>();
            for (JsonNode artifact : manifest.path("artifacts")) {
                names.add(artifact.path("name").asString());
            }
            return names;
        }

        public String artifactSha256(String name) {
            return artifactEntry(name).path("sha256").asString("");
        }

        public long artifactSizeBytes(String name) {
            return artifactEntry(name).path("size_bytes").asLong();
        }

        private JsonNode artifactEntry(String name) {
            for (JsonNode artifact : manifest.path("artifacts")) {
                if (artifact.path("name").asString("").equals(name)) {
                    return artifact;
                }
            }
            return MAPPER.createObjectNode();
        }
    }
}
