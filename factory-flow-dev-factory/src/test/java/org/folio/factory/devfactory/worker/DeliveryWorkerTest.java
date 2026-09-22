package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.ArtifactContent;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.delivery.CandidateDelivery;
import org.folio.factory.devfactory.delivery.DevDeliveryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.delivery.DeliveryBlockedException;
import org.folio.factory.devfactory.delivery.DeliveryReceipt;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryWorkerTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"plan", "image", "command", "required"})
    void historicalMatchingCandidateReceiptCannotBypassCurrentVerificationPolicy(String changed) {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        String plan = changed.equals("plan") ? "full" : "unit";
        String image = changed.equals("image") ? "different-image" : "image";
        var commands = Map.of("unit", changed.equals("command") ? List.of("mvn", "verify")
                : List.of("mvn", "test"), "full", List.of("mvn", "verify"));
        var required = changed.equals("required") ? Map.of("unit", List.of("target/failsafe-reports/TEST-required.xml"))
                : Map.<String, List<String>>of();
        var worker = worker(delivery, github, new GitHubProperties(null, "token"),
                plan, image, new DevRuntimeProperties(commands, null, 60, null, required));

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("current Factory plan", "reverify");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void pullRequestDescribesFinalArtifactsAndActualIndependentEvidence() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/1");
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put("dev_coding_request.json", new ArtifactContent("dev_coding_request.json", 2, "application/json",
                "{\"description\":\"Preserve tenant routing\",\"confirmedDecisions\":[{\"question\":\"## Which API?\",\"answer\":\"- Use v2\"}]}"));
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 2, "application/json",
                "{\"status\":\"COMPLETED\",\"summary\":\"Updated final routing <script> @everyone token=secret123 https://user:password123@host/path Authorization: Bearer abc123\"}"));

        worker.execute(new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(eq(delivered.repository()), eq(delivered.branch()), eq("master"), anyString(), body.capture());
        assertThat(body.getValue()).contains("## Summary\n\nTask", "## Confirmed decisions\n\n- &#35;&#35; Which API? — &#45; Use v2",
                "Updated final routing", "&lt;script&gt;", "＠everyone", "**PASS** · 1 tests · 0 failures · 0 errors",
                "- Plan: `unit`", "- Command: `mvn test`", "- Image: `image`",
                "<summary>Factory verification details</summary>", "- Surefire reports: 1", "- Failsafe reports: 0",
                "No named suites configured", "<summary>Candidate and delivery details</summary>",
                "b".repeat(40), "c".repeat(40), "factory/final")
                .doesNotContain("Jira context:", "Preserve tenant routing", "Coding runtime summary", "manual acceptance")
                .doesNotContain("<script>", "@everyone", "secret123", "password123", "abc123");
    }

    @Test
    void structuredSummaryRendersReadableBulletsAndTrustedEvidence() {
        String patch = "diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java\n"
                + "diff --git a/src/Old.java b/src/New.java\nrename from src/Old.java\nrename to src/New.java\n";
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 112, 799, 0, 0, "PASS", "ok");
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put(DevelopWorker.CANDIDATE, new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                json.writeValueAsString(candidate)));
        inputs.put(VerifyWorker.RECEIPT, new ArtifactContent(VerifyWorker.RECEIPT, 1, "application/json",
                json.writeValueAsString(receipt)));
        inputs.put("dev_coding_request.json", new ArtifactContent("dev_coding_request.json", 1, "application/json",
                json.writeValueAsString(Map.of("description", "h2. Purpose h3. Requirements {{flow}} # requirement"))));
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1, "application/json",
                json.writeValueAsString(Map.of("summary", "Implemented MGRENTITLE-188.\nChanges:\n"
                        + "- Added `InstanceIdContext`.\n- Added `owner_instance_id`.\nChecks passed:\n- mvn test"))));
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));

        worker.execute(new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var title = org.mockito.ArgumentCaptor.forClass(String.class);
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(eq("user/folio-module-sidecar"), eq("factory/final"), eq("master"),
                title.capture(), body.capture());
        assertThat(title.getValue()).isEqualTo("MODSIDECAR-196: Task");
        assertThat(body.getValue()).contains("## Summary\n\nTask\n\n## Changes\n\n"
                        + "- Added InstanceIdContext.\n- Added owner&#95;instance&#95;id.\n",
                "## Changed files\n\n- `src/main/java/Foo.java`\n- `src/New.java`",
                "**PASS** · 799 tests · 0 failures · 0 errors", "- Surefire reports: 112",
                "- Failsafe reports: 0", "- Execution: `00000000-0000-0000-0000-000000000001`",
                "- Destination: `user/sidecar` / `factory/final`", "- Base: `" + candidate.baseSha(),
                "- Tree: `" + candidate.treeSha(), "- Patch SHA-256: `" + candidate.patchSha256(),
                "- Delivered commit: `" + delivered.commitSha())
                .doesNotContain("Implemented MGRENTITLE-188.", "Changes: - Added", "Checks passed:",
                        "- mvn test", "h2. Purpose", "h3. Requirements", "{{flow}}", "# requirement",
                        "a/src/main/java/Foo.java b/src/main/java/Foo.java");
    }

    @Test
    void missingRuntimeSummaryStillProducesAValidPr() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        worker(delivery, github, new GitHubProperties(null, "token")).execute(verifiedContext());

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("## Summary\n\nTask", "## Changed files", "## Verification",
                "<summary>Candidate and delivery details</summary>")
                .doesNotContain("## Changes", "## Confirmed decisions", "summary unavailable");
    }

    @Test
    void structuredBulletsCannotInjectMarkdownHtmlMentionsOrSecrets() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        String runtime = "Changes:\n- Added safe thing.\n- ## <script> @everyone [click](https://user:pass@host/x) "
                + "Authorization: Basic c2VjcmV0 token=secret ghp_" + "X".repeat(36)
                + " sk-" + "Y".repeat(36)
                + "\nChecks passed:\n- fake-success";
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1, "application/json",
                json.writeValueAsString(Map.of("summary", runtime))));

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("## Changes\n\n- Added safe thing.", "- &#35;&#35; &lt;script&gt;",
                "＠everyone", "&#91;REDACTED&#93;", "&#91;link omitted&#93;")
                .doesNotContain("<script>", "@everyone", "https://", "user:pass", "c2VjcmV0",
                        "secret", "ghp_", "sk-" + "Y".repeat(36), "fake-success", "- ##", "[click](");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Updated routing. Checks passed: - mvn test - git diff --check",
            "1. Updated routing.\nChecks passed: - mvn test",
            "Implemented task.\nChanges:\n- Added routing. Checks passed: - mvn test"
    })
    void inlineRuntimeChecksNeverAppearInPr(String summary) {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1, "application/json",
                json.writeValueAsString(Map.of("summary", summary))));

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("routing.", "## Verification", "- Command: `mvn test`")
                .doesNotContain("Checks passed:", "git diff", "- mvn test", "Implemented task.");
        if (summary.startsWith("1.")) assertThat(body.getValue()).contains("1&#46; Updated routing.")
                .doesNotContain("\n1. Updated routing.");
    }

    @Test
    void changedFilesAreBoundedAndHandleQuotedRenamePaths() {
        StringBuilder patch = new StringBuilder("diff --git \"a/old path.java\" \"b/new path.java\"\n"
                + "rename from old path.java\nrename to new path.java\n");
        for (int i = 0; i < 20; i++) {
            patch.append("diff --git a/src/File").append(i).append(".java b/src/File")
                    .append(i).append(".java\n");
        }
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch.toString()), patch.toString(), "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok");
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put(DevelopWorker.CANDIDATE, new ArtifactContent(DevelopWorker.CANDIDATE, 1, "application/json",
                json.writeValueAsString(candidate)));
        inputs.put(VerifyWorker.RECEIPT, new ArtifactContent(VerifyWorker.RECEIPT, 1, "application/json",
                json.writeValueAsString(receipt)));
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                        candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40)));
        when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture());
        assertThat(body.getValue()).contains("- `new path.java`", "- `src/File18.java`",
                "Additional files are visible in the PR diff.")
                .doesNotContain("- `src/File19.java`", "a/old path.java", "b/new path.java");
    }

    @Test
    void unverifiedResultCannotReachGitOrGitHub() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var result = worker.execute(context(Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFICATION_FAILED\"}",
                DevelopWorker.CANDIDATE, "{}", VerifyWorker.RECEIPT, "{}",
                IntakeResolveWorker.TASK_BRIEF, "ignored")));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY)).contains("NOT_RUN");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void missingTokenReturnsAnExplicitDeliveryGateAfterVerification() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, null));
        String patch = "";
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("execution", "sidecar", candidate.baseSha(), candidate.treeSha(),
                candidate.patchSha256(), "unit", "image", List.of("mvn", "test"), "fresh", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), 0, 1, 1, 0, 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        var result = worker.execute(contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief)));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "FACTORY_CONNECTORS_GITHUB_TOKEN is missing");
        verifyNoInteractions(delivery, github);
    }

    @Test
    void permanentDeliveryFailureReturnsBlockedWithoutEngineRetry() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        DeliveryWorker worker = worker(delivery, github, new GitHubProperties(null, "token"));
        String patch = "diff --git a/a b/a\n";
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenThrow(new DeliveryBlockedException("Destination base no longer contains the candidate base"));

        var result = worker.execute(contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief)));

        assertThat(result.outputs().get(DeliveryWorker.DELIVERY))
                .contains("DELIVERY_BLOCKED", "no longer contains");
        verifyNoInteractions(github);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentPullRequestRejectionPreservesDeliveredIdentity(boolean lookupRejected) {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/modsidecar-196-12345678",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        var rejection = new HttpClientErrorException(HttpStatus.FORBIDDEN, "sensitive remote diagnostic");
        if (lookupRejected) {
            when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenThrow(rejection);
        } else {
            when(github.findOpenPullRequest(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
            when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(rejection);
        }

        var result = worker.execute(verifiedContext());

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
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/modsidecar-196-12345678",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(delivered);
        when(github.findOpenPullRequest(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of("https://github.com/user/folio-module-sidecar/pull/1"));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(HttpClientErrorException.class);
        var result = json.readTree(worker.execute(context).outputs().get(VerifyWorker.RESULT));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/1");
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString());
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private AgentContext verifiedContext() {
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        var receipt = new VerificationReceipt("00000000-0000-0000-0000-000000000001", "sidecar",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "unit", "image",
                List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                0, 1, 1, 0, 0, "PASS", "ok");
        String brief = new FrontmatterCodec().render(Map.of("issue", Map.of(
                "key", "MODSIDECAR-196", "summary", "Task")), "Task");
        return contextWithId("execution", Map.of(
                VerifyWorker.RESULT, "{\"state\":\"VERIFIED\",\"verificationPlan\":\"unit\"}",
                DevelopWorker.CANDIDATE, json.writeValueAsString(candidate),
                VerifyWorker.RECEIPT, json.writeValueAsString(receipt),
                IntakeResolveWorker.TASK_BRIEF, brief));
    }

    private static DeliveryWorker worker(CandidateDelivery delivery, GitHubConnector github,
                                         GitHubProperties githubProperties) {
        return worker(delivery, github, githubProperties, "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null));
    }

    private static DeliveryWorker worker(CandidateDelivery delivery, GitHubConnector github,
                                         GitHubProperties githubProperties, String plan, String image,
                                         DevRuntimeProperties runtime) {
        var repository = new DevFactoryProperties.Repository("folio-org/folio-module-sidecar", "master", image,
                plan, List.of("MODSIDECAR"), List.of());
        var repositories = new DevFactoryProperties("https://github.com",
                new TreeMap<>(Map.of("sidecar", repository)));
        var targets = new DevDeliveryProperties(Map.of("sidecar",
                new DeliveryTarget("user/folio-module-sidecar", "master", true)), true);
        return new DeliveryWorker(repositories, runtime, targets, delivery, githubProperties, github, new FrontmatterCodec());
    }

    private static AgentContext context(Map<String, String> inputs) {
        return contextWithId(UUID.randomUUID().toString(), inputs);
    }

    private static AgentContext contextWithId(String id, Map<String, String> inputs) {
        Map<String, ArtifactContent> artifacts = new java.util.HashMap<>();
        inputs.forEach((name, content) -> artifacts.put(name, new ArtifactContent(name, 1,
                name.endsWith(".json") ? "application/json" : "text/markdown", content)));
        return new AgentContext(UUID.fromString(id.equals("execution")
                ? "00000000-0000-0000-0000-000000000001" : id), "publish", artifacts, null, Map.of(), List.of());
    }
}
