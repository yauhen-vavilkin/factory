package org.folio.factory.app.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.runtime.CodingOutcome;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.Model;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelPrice;
import org.folio.factory.devfactory.runtime.Processes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Helpers for the tests that run the Developer Flow on the real engine: local Git repositories the
 * flow resolves its bases from, a scripted Jira issue, and bounded waits for and reads of an
 * execution's status, reviews and artifacts. The scripted boundaries of a task run live here too, so a
 * test can say "the agent does X, the checks do Y": the coding runtime returns a {@link CodingOutcome},
 * Docker runs the starting build, exports the agent's workspace and the checks' reports, delivery pushes
 * nothing, and WireMock answers for GitHub and Jira.
 */
public final class DevFlowHarness {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSING_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="FixtureSuite" tests="2" skipped="0" failures="0" errors="0" time="0.01">
              <testcase name="works" classname="FixtureSuite" time="0.005"/>
              <testcase name="alsoWorks" classname="FixtureSuite" time="0.005"/>
            </testsuite>
            """;
    private static final Processes.Result BUILD_SUCCESS = new Processes.Result(0, "[INFO] BUILD SUCCESS");

    /** The scripted containers of one run; each test re-stubs the one whose result it changes. */
    public record Workloads(DockerWorkloads.Workload baseline, DockerWorkloads.Workload coding,
                            DockerWorkloads.Workload verification) {

        /** The starting build on the base commit ends with this result. */
        public void startingBuildReturns(Processes.Result result) {
            when(baseline.executeKeepingTail(anyList(), anyInt(), anyInt(), any())).thenReturn(result);
        }

        /** The agent's container holds the files of this workspace when Factory exports it. */
        public void agentLeaves(Path workspace) {
            exportsFixture(coding, workspace);
        }

        /** The checks end with this result and leave these reports in the exported workspace. */
        public void checksReturn(Processes.Result result, Path reports) {
            when(verification.executeKeepingTail(anyList(), anyInt(), eq(Processes.OUTPUT_LIMIT), isNull())).thenReturn(result);
            exportsFixture(verification, reports);
        }
    }

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

    /** Answers a Jira search (the first page) with these issues, built by {@link #issue}. */
    public static void stubSearch(WireMockServer jira, String... issues) {
        jira.stubFor(post(urlEqualTo("/rest/api/2/search/jql"))
                .withRequestBody(matchingJsonPath("$[?(!@.nextPageToken)]"))
                .willReturn(okJson("{\"issues\": [" + String.join(", ", issues) + "]}")));
    }

    /** One open story of a search result; {@code extraFields} are raw JSON members added to its fields. */
    public static String issue(String id, String key, String project, String summary, String created,
                               String extraFields) {
        return """
                {"id": "%s", "key": "%s", "fields": {"summary": "%s", "description": "Description of %s", \
                "status": {"name": "To Do"}, "issuetype": {"name": "Story"}, "labels": ["backend"], \
                "created": "%s", "components": [{"name": "api"}], \
                "project": {"key": "%s"}%s}}""".formatted(id, key, summary, key, created, project, extraFields);
    }

    /** GitHub finds no pull request of the repository and creates the next one at this URL. */
    public static void stubPullRequestCreation(WireMockServer github, String repository, String pullRequestUrl) {
        github.stubFor(get(urlPathEqualTo("/repos/" + repository + "/pulls"))
                .withQueryParam("state", equalTo("all")).willReturn(okJson("[]")));
        github.stubFor(post(urlPathEqualTo("/repos/" + repository + "/pulls"))
                .willReturn(okJson("{\"html_url\": \"" + pullRequestUrl + "\"}")));
    }

    /**
     * The open pull request and its empty issue comments, reviews and review comments, which the
     * follow-up step reads after publish.
     */
    public static void stubPullRequestRead(WireMockServer github, String repository, int number, String headBranch) {
        String pull = "/repos/" + repository + "/pulls/" + number;
        github.stubFor(get(urlEqualTo(pull))
                .willReturn(okJson("{\"number\": " + number + ", \"state\": \"open\", \"merged\": false,"
                        + "\"html_url\": \"https://github.com/" + repository + "/pull/" + number + "\","
                        + "\"head\": {\"ref\": \"" + headBranch + "\", \"sha\": \"" + "d".repeat(40) + "\","
                        + "\"repo\": {\"full_name\": \"" + repository + "\"}},"
                        + "\"base\": {\"ref\": \"master\", \"repo\": {\"full_name\": \"" + repository + "\"}}}")));
        for (String list : List.of("/repos/" + repository + "/issues/" + number + "/comments",
                pull + "/reviews", pull + "/comments")) {
            github.stubFor(get(urlPathEqualTo(list)).willReturn(okJson("[]")));
        }
    }

    /**
     * The scripted agent: every coding request gets the outcome {@code agent} returns. The runtime
     * redacts nothing and prices its model at a fixed rate card.
     */
    public static void stubCodingRuntime(CodingRuntime coding, ModelCatalog models,
                                         Function<CodingRequest, CodingOutcome> agent) {
        when(coding.id()).thenReturn("stub");
        // Real runtimes return the text unchanged when no secret matches; a bare mock would return null.
        when(coding.redact(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(coding.image()).thenReturn("stub:image");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "stub", "image", "stub:image",
                "model", "claude-sonnet-5-5"));
        var price = new ModelPrice("USD", LocalDate.of(2026, 10, 22), URI.create("https://pricing.fixture.invalid"),
                BigDecimal.valueOf(2), BigDecimal.valueOf(0.1), BigDecimal.ONE, null, null, BigDecimal.TEN);
        when(models.pricedEntry(any(), any())).thenReturn(Optional.of(new Model("sonnet", "anthropic-messages",
                "https://gateway.fixture.invalid", "bearer-token", "SONNET_TOKEN", null, "claude-sonnet-5-5",
                List.of("high"), "high", 16384, price, false)));
        when(coding.code(any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> agent.apply(invocation.getArgument(1)));
    }

    /**
     * Scripted Docker: the starting build passes, the agent leaves {@code agentWorkspace} and the checks
     * pass with {@code reports}.
     */
    public static Workloads stubWorkloads(DockerWorkloads docker, Path agentWorkspace, Path reports) {
        var scope = mock(DockerWorkloads.StepScope.class);
        var workloads = new Workloads(mock(DockerWorkloads.Workload.class), mock(DockerWorkloads.Workload.class),
                mock(DockerWorkloads.Workload.class));
        when(docker.beginStep(any(), anyString())).thenReturn(scope);
        when(scope.createTrusted(anyString(), any(), any())).thenReturn(workloads.baseline());
        when(scope.createSeeded(anyString(), any(), any(), eq("coding"))).thenReturn(workloads.coding());
        when(scope.createSeeded(anyString(), any(), any(), eq("verification")))
                .thenReturn(workloads.verification());
        when(workloads.baseline().name()).thenReturn("stub-baseline");
        when(workloads.coding().name()).thenReturn("stub-coding");
        when(workloads.verification().name()).thenReturn("stub-verification");
        workloads.startingBuildReturns(BUILD_SUCCESS);
        workloads.agentLeaves(agentWorkspace);
        workloads.checksReturn(BUILD_SUCCESS, reports);
        return workloads;
    }

    /** Delivery pushes nothing and returns the receipt of the pushed branch; {@code onDelivery} sees it first. */
    public static void stubDelivery(CandidateDelivery delivery, Consumer<DeliveryReceipt> onDelivery) {
        when(delivery.deliver(argThat(DevFlowHarness::isComplete))).thenAnswer(invocation -> {
            DeliveryReceipt receipt = delivered(invocation.getArgument(0));
            onDelivery.accept(receipt);
            return receipt;
        });
    }

    private static DeliveryReceipt delivered(CandidateDelivery.Request request) {
        Candidate candidate = request.candidate();
        return new DeliveryReceipt(request.executionId(), request.target().repository(),
                CandidateDelivery.branchFor(request.executionId(), request.issueKey(), candidate,
                        request.stableBranch()),
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "d".repeat(40));
    }

    /** A directory with these files, for the agent's exported workspace or the checks' reports. */
    public static Path workspace(String prefix, Map<String, String> files) {
        try {
            Path directory = Files.createTempDirectory(prefix);
            files.forEach((name, content) -> write(directory.resolve(name), content));
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Surefire reports of two passing tests. */
    public static Path passingReports() {
        return workspace("dev-flow-reports-", Map.of("target/surefire-reports/TEST-FixtureSuite.xml", PASSING_REPORT));
    }

    /** The page text a person reads: markup removed, entities for quotes decoded, spaces collapsed. */
    public static String visibleText(String html) {
        return html.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&").replaceAll("\\s+", " ");
    }

    /** Every artifact the Developer Flow steps declare as outputs, each name once. */
    public static List<String> declaredOutputs(FlowRegistry flowRegistry) {
        return flowRegistry.require("dev-factory").agentChain().stream()
                .flatMap(step -> step.outputs().stream())
                .distinct()
                .toList();
    }

    /** The delivery worker fills every request field; a stub that accepted null would hide a regression. */
    public static boolean isComplete(CandidateDelivery.Request request) {
        return request != null && request.executionId() != null && request.issueKey() != null
                && request.summary() != null && request.sourceUrl() != null && request.githubToken() != null;
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

    private static void exportsFixture(DockerWorkloads.Workload workload, Path fixture) {
        doAnswer(invocation -> {
            copyFixture(fixture, invocation.getArgument(0, Path.class));
            return null;
        }).when(workload).export(any());
    }

    private static void copyFixture(Path source, Path target) {
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path destination = target.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot export fixture workspace", e);
        }
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
