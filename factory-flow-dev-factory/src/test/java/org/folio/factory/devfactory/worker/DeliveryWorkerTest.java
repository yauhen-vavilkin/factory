package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.service.StateManager;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.DevFlowSettings;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.delivery.DeliveryServices;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.tasks.AttemptDeadline;
import org.folio.factory.devfactory.tasks.RecordingTaskAttempts;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The pull request body itself is covered by {@code PullRequestRendererTest}; this class covers the delivery step. */
class DeliveryWorkerTest {
    private static final String EXECUTION_ID = "00000000-0000-0000-0000-000000000001";
    private static final DevRuntimeProperties UNIT_TESTS =
            new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null, null);
    private static final DeliveryTarget USER_FORK = new DeliveryTarget("user/folio-module-sidecar", "master");
    private static final GitHubProperties WITH_TOKEN = new GitHubProperties(null, "token");

    private final JsonMapper json = JsonMapper.builder().build();
    private final CandidateDelivery delivery = mock(CandidateDelivery.class);
    private final GitHubConnector github = mock(GitHubConnector.class);
    private final Candidate candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
            Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
    private final DeliveryReceipt delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar",
            "factory/final", candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40));

    @ParameterizedTest
    @ValueSource(strings = {"plan", "image", "command", "required"})
    void historicalMatchingCandidateReceiptCannotBypassCurrentVerificationPolicy(String changed) {
        String plan = changed.equals("plan") ? "full" : "unit";
        String image = changed.equals("image") ? "different-image" : "image";
        var commands = Map.of("unit", changed.equals("command") ? List.of("mvn", "verify")
                : List.of("mvn", "test"), "full", List.of("mvn", "verify"));
        var required = changed.equals("required") ? Map.of("unit", List.of("failsafe"))
                : Map.<String, List<String>>of();
        var worker = worker(plan, image, new DevRuntimeProperties(commands, null, 60, null, required));

        var result = delivery(worker.execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("current Factory plan", "reverify");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void policyIsCheckedAgainBeforeCreatingPullRequest() {
        var runtime = mock(DevRuntimeProperties.class);
        when(runtime.command("unit")).thenReturn(List.of("mvn", "test"), List.of("mvn", "verify"));
        when(runtime.requiredReports("unit")).thenReturn(List.of());
        givenDelivered();

        var result = delivery(worker("unit", "image", runtime).execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString())
                .contains("no longer matches the current Factory plan", "reverify");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        verify(delivery, times(1)).deliver(any());
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void pullRequestDescribesFinalArtifactsAndActualIndependentEvidence() {
        givenDelivered();
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/1");
        var context = withInputs(Map.of(
                IntakeResolveWorker.CODING_REQUEST, "{\"description\":\"Preserve tenant routing\",\"confirmedDecisions\":"
                        + "[{\"question\":\"## Which API?\",\"answer\":\"- Use v2\"}]}",
                DevelopWorker.OUTCOME, "{\"status\":\"COMPLETED\",\"summary\":\"Updated final routing <script> @everyone"
                        + " token=secret123 https://user:password123@host/path Authorization: Bearer abc123\"}"));

        var result = worker().execute(context);

        for (String artifact : List.of(DeliveryWorker.DELIVERY, VerifyWorker.RESULT)) {
            var written = json.readTree(result.outputs().get(artifact));
            assertThat(written.path("pullRequestTitle").asString()).isEqualTo("MODSIDECAR-196: Task");
            // The final message does not use the report template, so the pull request is a draft.
            assertThat(written.path("pullRequestDraft").asBoolean()).isTrue();
            assertThat(written.path("draftReasons").get(0).asString()).contains("does not use the four expected sections");
        }
        var body = ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(eq(delivered.repository()), eq(delivered.branch()), eq("master"),
                eq("MODSIDECAR-196: Task"), body.capture(), eq(true));
        assertThat(body.getValue()).doesNotContain("## Summary", "\nTask\n");
        assertThat(body.getValue()).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)");
        assertThat(body.getValue()).contains("## Confirmed decisions\n\n- &#35;&#35; Which API? — &#45; Use v2",
                "## Agent report\n\nUpdated final routing", "&lt;script&gt;", "＠everyone", "**PASS** · 1 tests · 0 failures · 0 errors",
                "- Plan: `unit`", "- Command: `mvn test`", "- Image: `image`",
                "<summary>Factory verification details</summary>", "- Surefire reports: 1", "- Failsafe reports: 0",
                "No named suites configured", "<summary>Candidate and delivery details</summary>",
                "b".repeat(40), "c".repeat(40), "factory/final")
                .doesNotContain("Jira context:", "Preserve tenant routing", "Coding runtime summary", "manual acceptance")
                .doesNotContain("<script>", "@everyone", "secret123", "password123", "abc123");
    }

    @Test
    void aReportInTheFourSectionsOpensAReadyPullRequest() {
        givenDelivered();

        var result = delivery(worker().execute(withOutcome("## Changes\n- Added the route.\n"
                + "## Acceptance criteria\n- Works: met\n## Assumptions\nNone\n## Could not read\nNone")));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestDraft").asBoolean(true)).isFalse();
        assertThat(result.path("draftReasons").isEmpty()).isTrue();
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), eq(false));
    }

    @Test
    void unverifiedResultCannotReachGitOrGitHub() {
        var result = worker().execute(context(Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFICATION_FAILED\"}",
                DevelopWorker.CANDIDATE, "{}", VerifyWorker.RECEIPT, "{}",
                IntakeResolveWorker.TASK_BRIEF, "ignored")));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY)).contains("NOT_RUN");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void missingTokenReturnsAnExplicitDeliveryGateAfterVerification() {
        var result = worker(new GitHubProperties(null, null)).execute(verifiedContext());

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void permanentDeliveryFailureReturnsBlockedWithoutEngineRetry() {
        when(delivery.deliver(completeRequest()))
                .thenThrow(new DeliveryBlockedException("Destination base no longer contains the candidate base"));

        var result = worker().execute(verifiedContext());

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "no longer contains");
        verifyNoInteractions(github);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentPullRequestRejectionPreservesDeliveredIdentity(boolean lookupRejected) {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        var rejection = new HttpClientErrorException(HttpStatus.FORBIDDEN, "sensitive remote diagnostic");
        if (lookupRejected) {
            when(github.findPullRequests(anyString(), anyString(), anyString())).thenThrow(rejection);
        } else {
            when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
            when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                    .thenThrow(rejection);
        }

        var result = worker().execute(verifiedContext());

        for (String artifact : List.of(DeliveryWorker.DELIVERY, VerifyWorker.RESULT)) {
            var output = json.readTree(result.outputs().get(artifact));
            assertThat(output.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
            assertThat(output.path("deliveryRepository").asString()).isEqualTo(delivered.repository());
            assertThat(output.path("deliveryBranch").asString()).isEqualTo(delivered.branch());
            assertThat(output.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
            assertThat(output.path("pullRequestState").asString()).isEqualTo("BLOCKED");
            assertThat(output.path("reason").asString()).contains("branch and commit delivered", "HTTP 403")
                    .doesNotContain("sensitive remote diagnostic");
        }
    }

    @Test
    void transientPullRequestFailureRetriesAndReusesExistingPullRequest() {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(), List.of(pr("https://github.com/user/folio-module-sidecar/pull/1",
                        "c".repeat(40), "open")));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        var worker = worker();
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(HttpClientErrorException.class);
        var result = json.readTree(worker.execute(context).outputs().get(VerifyWorker.RESULT));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/1");
        assertThat(result.has("pullRequestTitle")).isFalse();
        verify(delivery, times(2)).deliver(any());
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aTaskAttemptThatLostItsSlotCannotPublish() {
        var taskAttempts = RecordingTaskAttempts.taskRun("sidecar");
        taskAttempts.ownsSlot = false;

        var result = delivery(worker(taskAttempts).execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("no longer owns");
        verifyNoInteractions(delivery, github);
        assertThat(taskAttempts.stops).singleElement().asString().startsWith("publish: ");
    }

    @Test
    void anExpiredTaskDeadlineCannotPublish() {
        var taskAttempts = RecordingTaskAttempts.taskRun("sidecar")
                .withDeadline(new AttemptDeadline(Instant.now().minusSeconds(1)));

        var result = delivery(worker(taskAttempts).execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).isEqualTo(TaskStops.deadlineReason("publication"));
        verifyNoInteractions(delivery, github);
    }

    @ParameterizedTest
    @CsvSource({"open, false", "closed, false", "closed, true"})
    void aPullRequestInAnyStateIsReusedInsteadOfCreatingADuplicate(String state, boolean merged) {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(new GitHubConnector.PullRequest(
                        "https://github.com/user/folio-module-sidecar/pull/9",
                        state, merged, "factory/final", "c".repeat(40), "master")));

        var result = delivery(worker().execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/9");
        // The same computation as for a new pull request: the context has no agent report, so it is a draft.
        assertThat(result.path("pullRequestDraft").asBoolean()).isTrue();
        assertThat(result.path("draftReasons")).hasSize(1);
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void lostCreateResponseIsRecoveredByStateCoveringLookupWithoutADuplicate() {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(), List.of(pr("https://github.com/user/folio-module-sidecar/pull/5", "c".repeat(40), "closed")));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("response lost after the remote accepted the call"));
        var worker = worker();
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(IllegalStateException.class);
        var result = delivery(worker.execute(context));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/5");
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void multipleMatchingPullRequestsStopForInspectionInsteadOfCreatingAnother() {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        pr("https://github.com/user/folio-module-sidecar/pull/1", "c".repeat(40), "closed"),
                        pr("https://github.com/user/folio-module-sidecar/pull/2", "c".repeat(40), "open")));

        var result = delivery(worker().execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("Multiple existing pull requests", "inspect");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void existingPullRequestHeadMismatchStopsForInspectionInsteadOfPublishing() {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pr("https://github.com/user/folio-module-sidecar/pull/3", "d".repeat(40), "open")));

        var result = delivery(worker().execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("head no longer matches the delivered");
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aFolioOrgDestinationPublishesBranchAndPullRequestThere() {
        var folioOrg = new DeliveryTarget("folio-org/folio-module-sidecar", "master");
        when(delivery.deliver(completeRequest())).thenReturn(new DeliveryReceipt("execution",
                "folio-org/folio-module-sidecar", "factory/final", "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40)));
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/folio-org/folio-module-sidecar/pull/11");

        var result = delivery(worker(WITH_TOKEN, "unit", "image", UNIT_TESTS, RecordingTaskAttempts.none(), folioOrg,
                FeedbackArtifacts.VERSION).execute(verifiedContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString())
                .isEqualTo("https://github.com/folio-org/folio-module-sidecar/pull/11");
        verify(delivery).deliver(argThat(request -> isComplete(request) && request.target().equals(folioOrg)));
        verify(github).createPullRequest(eq("folio-org/folio-module-sidecar"), eq("factory/final"),
                eq("master"), anyString(), anyString(), anyBoolean());
    }

    @Test
    void changedConsumedInputStopsBeforeAnyRemoteWriteAndPreservesTheCandidate() {
        var taskAttempts = RecordingTaskAttempts.taskRun("sidecar");
        taskAttempts.authorityFailure = new DeliveryBlockedException("the task's requirements changed after admission");

        var result = delivery(worker(taskAttempts).execute(taskContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("requirements changed after admission");
        assertThat(taskAttempts.authorityChecks).hasSize(1);
        assertThat(taskAttempts.intents).isEmpty();
        verifyNoInteractions(delivery, github);
    }

    @Test
    void authorityFailureOnTheSecondRecheckStopsBeforePullRequestCreation() {
        var taskAttempts = RecordingTaskAttempts.taskRun("sidecar");
        taskAttempts.authorityFailure = new DeliveryBlockedException("the task's requirements changed after admission");
        taskAttempts.authorityFailureOnCall = 2;
        givenDelivered();

        var result = delivery(worker(taskAttempts).execute(taskContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("requirements changed after admission");
        assertThat(taskAttempts.authorityChecks).hasSize(2);
        assertThat(taskAttempts.intents).hasSize(1);
        assertThat(result.path("repository").asString()).isEqualTo(candidate.repository());
        assertThat(result.path("patchSha256").asString()).isEqualTo(candidate.patchSha256());
        verify(delivery, times(1)).deliver(any());
        verify(github, times(1)).findPullRequests(anyString(), anyString(), anyString());
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @ParameterizedTest
    @CsvSource({
            FeedbackArtifacts.VERSION + ", true, factory/modsidecar-196-00000000-0000-0000-0000-000000000001",
            // Runs of the flow before PR feedback keep the branch named after the patch.
            "0.8.0, false, factory/modsidecar-196-e3b0c442"})
    void publicationIntentIsRecordedBeforeTheFirstRemoteMutation(String flowVersion, boolean stableBranch,
                                                                  String branch) {
        var taskAttempts = RecordingTaskAttempts.taskRun("sidecar");
        when(delivery.deliver(completeRequest())).thenAnswer(invocation -> {
            assertThat(taskAttempts.intents).hasSize(1);
            return new DeliveryReceipt("execution", "user/folio-module-sidecar", taskAttempts.intents.get(0).headBranch(),
                    candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40));
        });
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/2");

        var result = delivery(worker(WITH_TOKEN, "unit", "image", UNIT_TESTS, taskAttempts, USER_FORK, flowVersion)
                .execute(taskContext()));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        var intent = taskAttempts.intents.get(0);
        assertThat(intent.repository()).isEqualTo("user/folio-module-sidecar");
        assertThat(intent.baseBranch()).isEqualTo("master");
        assertThat(intent.headBranch()).isEqualTo(branch);
        assertThat(intent.baseSha()).isEqualTo(candidate.baseSha());
        assertThat(intent.treeSha()).isEqualTo(candidate.treeSha());
        assertThat(intent.patchSha256()).isEqualTo(candidate.patchSha256());
        verify(delivery).deliver(argThat(request -> request.stableBranch() == stableBranch));
    }

    private void givenDelivered() {
        when(delivery.deliver(completeRequest())).thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
    }

    private JsonNode delivery(org.folio.factory.core.agent.AgentResult result) {
        return json.readTree(result.outputs().get(DeliveryWorker.DELIVERY));
    }

    private AgentContext verifiedContext() {
        var receipt = new VerificationReceipt(EXECUTION_ID, "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok", null, 0, List.of(), List.of());
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        return context(Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief));
    }

    /** The verified context with the agent's final message as {@code summary}. */
    private AgentContext withOutcome(String summary) {
        return withInputs(Map.of(DevelopWorker.OUTCOME, json.writeValueAsString(Map.of("summary", summary))));
    }

    private AgentContext withInputs(Map<String, String> extra) {
        var original = verifiedContext();
        var inputs = new HashMap<>(original.inputs());
        extra.forEach((name, content) -> inputs.put(name, new ArtifactContent(name, 2, "application/json", content)));
        return new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of());
    }

    private AgentContext taskContext() {
        return withInputs(Map.of(IntakeResolveWorker.CODING_REQUEST, json.writeValueAsString(taskCodingRequest())));
    }

    private DeliveryWorker worker() {
        return worker(WITH_TOKEN);
    }

    private DeliveryWorker worker(GitHubProperties githubProperties) {
        return worker(githubProperties, "unit", "image", UNIT_TESTS, RecordingTaskAttempts.none(), USER_FORK,
                FeedbackArtifacts.VERSION);
    }

    private DeliveryWorker worker(String plan, String image, DevRuntimeProperties runtime) {
        return worker(WITH_TOKEN, plan, image, runtime, RecordingTaskAttempts.none(), USER_FORK,
                FeedbackArtifacts.VERSION);
    }

    private DeliveryWorker worker(RecordingTaskAttempts taskPolicy) {
        return worker(WITH_TOKEN, "unit", "image", UNIT_TESTS, taskPolicy, USER_FORK, FeedbackArtifacts.VERSION);
    }

    private DeliveryWorker worker(GitHubProperties githubProperties, String plan, String image,
                                  DevRuntimeProperties runtime, RecordingTaskAttempts taskPolicy,
                                  DeliveryTarget destination, String flowVersion) {
        var repository = new DevFactoryProperties.Repository(
                "folio-org/folio-module-sidecar", "master", image, plan, List.of("MODSIDECAR"), List.of(),
                destination);
        var repositories = new DevFactoryProperties("https://github.com",
                new TreeMap<>(Map.of("sidecar", repository)));
        var state = mock(StateManager.class);
        when(state.get(any())).thenReturn(new PipelineExecution("dev-factory", flowVersion, null));
        return new DeliveryWorker(new DevFlowSettings(repositories, runtime),
                new DeliveryServices(delivery, github, githubProperties),
                new JiraProperties("https://jira.example.org", null, null),
                new FrontmatterCodec(), state, taskPolicy.guard());
    }

    private static GitHubConnector.PullRequest pr(String url, String headSha, String state) {
        return new GitHubConnector.PullRequest(url, state, false, "factory/final", headSha, "master");
    }

    private static CodingRequest taskCodingRequest() {
        return new CodingRequest(
                "MODSIDECAR-196", "Task", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget(
                        "sidecar", "folio-org/folio-module-sidecar", "master", "a".repeat(40)),
                null, null);
    }

    private static AgentContext context(Map<String, String> inputs) {
        Map<String, ArtifactContent> artifacts = new HashMap<>();
        inputs.forEach((name, content) -> artifacts.put(name, new ArtifactContent(name, 1,
                name.endsWith(".json") ? "application/json" : "text/markdown", content)));
        return new AgentContext(UUID.fromString(EXECUTION_ID), "publish", artifacts, null, Map.of(), List.of());
    }

    /** Matches a request with all text fields set, as the old {@code anyString()} matchers did. */
    private static CandidateDelivery.Request completeRequest() {
        return argThat(DeliveryWorkerTest::isComplete);
    }

    private static boolean isComplete(CandidateDelivery.Request request) {
        return request != null && request.executionId() != null && request.issueKey() != null
                && request.summary() != null && request.sourceUrl() != null && request.githubToken() != null;
    }
}
