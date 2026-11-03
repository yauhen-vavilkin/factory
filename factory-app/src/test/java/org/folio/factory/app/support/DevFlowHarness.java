package org.folio.factory.app.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Helpers for the tests that run the Developer Flow on the real engine: local Git repositories the
 * flow resolves its bases from, a scripted Jira issue, and bounded waits for and reads of an
 * execution's status, reviews and artifacts.
 */
public final class DevFlowHarness {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StateManager stateManager;
    private final ArtifactStore artifactStore;
    private final HitlReviewRepository reviews;
    private final FrontmatterCodec codec = new FrontmatterCodec();

    public DevFlowHarness(StateManager stateManager, ArtifactStore artifactStore, HitlReviewRepository reviews) {
        this.stateManager = stateManager;
        this.artifactStore = artifactStore;
        this.reviews = reviews;
    }

    public static Path createGitRoot(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A bare repository {@code <sourceRepo>.git} with one empty commit on the branch; returns that commit. */
    public static String createRepository(Path gitRoot, String sourceRepo, String branch) {
        return git(createRepositoryWithFiles(gitRoot, sourceRepo, branch, Map.of()), "rev-parse", "HEAD").strip();
    }

    /** A bare repository {@code <sourceRepo>.git} with one commit of the files; returns the working copy. */
    public static Path createRepositoryWithFiles(Path gitRoot, String sourceRepo, String branch,
                                                 Map<String, String> files) {
        Path bare = gitRoot.resolve(sourceRepo + ".git");
        Path work = gitRoot.resolve("work-" + sourceRepo.replace('/', '-'));
        git(gitRoot, "init", "--bare", "-b", branch, bare.toString());
        git(gitRoot, "init", "-b", branch, work.toString());
        files.forEach((name, content) -> write(work.resolve(name), content));
        git(work, "add", ".");
        git(work, "-c", "user.name=fixture", "-c", "user.email=fixture@example.org",
                "commit", "--allow-empty", "-m", "init " + sourceRepo);
        git(work, "push", bare.toString(), branch);
        return work;
    }

    public static String git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException(command + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Serves an open backend story; {@code extraFields} are raw JSON members added to its fields. */
    public static void stubIssue(WireMockServer jira, String key, String description, String extraFields) {
        String fields = JSON.writeValueAsString(Map.of(
                "summary", "Summary of " + key,
                "description", description,
                "status", Map.of("name", "Open"),
                "issuetype", Map.of("name", "Story"),
                "labels", List.of("backend")));
        if (!extraFields.isBlank()) {
            fields = fields.substring(0, fields.length() - 1) + "," + extraFields + "}";
        }
        jira.stubFor(get(urlEqualTo("/rest/api/2/issue/" + key)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\": \"" + key + "\", \"fields\": " + fields + "}")));
    }

    public void awaitStatus(UUID executionId, ExecutionStatus status) {
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(stateManager.get(executionId).getStatus()).isEqualTo(status));
    }

    /** Waits for the gate's pending review and returns it; it must be the execution's only review. */
    public HitlReview awaitPendingReview(UUID executionId, String gateId) {
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(reviews.findByExecutionIdOrderByCreatedAtAsc(executionId))
                        .anyMatch(r -> r.getGateId().equals(gateId) && r.getStatus() == HitlReviewStatus.PENDING));
        List<HitlReview> all = reviews.findByExecutionIdOrderByCreatedAtAsc(executionId);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    public String latest(UUID executionId, String name) {
        return artifactStore.getLatest(executionId, name).orElseThrow().getContent();
    }

    public JsonNode metadata(UUID executionId, String name) {
        return codec.parse(latest(executionId, name)).metadata();
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
