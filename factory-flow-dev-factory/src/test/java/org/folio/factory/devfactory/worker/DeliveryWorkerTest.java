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
    void policyIsCheckedAgainBeforeCreatingPullRequest() {
        var delivery = mock(CandidateDelivery.class);
        var github = mock(GitHubConnector.class);
        var runtime = mock(DevRuntimeProperties.class);
        when(runtime.command("unit")).thenReturn(List.of("mvn", "test"), List.of("mvn", "verify"));
        when(runtime.requiredReports("unit")).thenReturn(List.of());
        var worker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image", runtime);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                        "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40)));
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo("c".repeat(40));
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void pullRequestDescribesFinalArtifactsAndActualIndependentEvidence() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/1");
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put("dev_coding_request.json", new ArtifactContent("dev_coding_request.json", 2, "application/json",
                "{\"description\":\"Preserve tenant routing\",\"confirmedDecisions\":[{\"question\":\"## Which API?\",\"answer\":\"- Use v2\"}]}"));
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 2, "application/json",
                "{\"status\":\"COMPLETED\",\"summary\":\"Updated final routing <script> @everyone token=secret123 https://user:password123@host/path Authorization: Bearer abc123\"}"));

        var result = worker.execute(new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));
        for (String artifact : List.of(DeliveryWorker.DELIVERY, VerifyWorker.RESULT)) {
            var written = json.readTree(result.outputs().get(artifact));
            assertThat(written.path("pullRequestTitle").asString()).isEqualTo("MODSIDECAR-196: Task");
            // The final message does not use the report template, so the pull request is a draft.
            assertThat(written.path("pullRequestDraft").asBoolean()).isTrue();
            assertThat(written.path("draftReasons").get(0).asString()).contains("does not use the four expected sections");
        }

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(eq(delivered.repository()), eq(delivered.branch()), eq("master"),
                eq("MODSIDECAR-196: Task"), body.capture(), eq(true));
        assertThat(body.getValue()).doesNotContain("## Summary", "\nTask\n");
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
                json.writeValueAsString(Map.of("summary", "Implemented MGRENTITLE-188.\n## Changes\n"
                        + "- Added `InstanceIdContext`.\n  - Used by the routing filter.\n- Added `owner_instance_id`.\n"
                        + "Conventions: NEWS.md entry.\nChecks passed:\n- mvn test\n"
                        + "## Acceptance criteria\n1. The id is stored: met, a new test covers it.\n"
                        + "2) The id is returned: met.\n## Assumptions\nNone.\n## Could not read\n- none\n"))));
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));

        var result = json.readTree(worker.execute(new AgentContext(original.executionId(), original.stepId(), inputs,
                null, Map.of(), List.of())).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("pullRequestDraft").asBoolean(true)).isFalse();
        assertThat(result.path("draftReasons").isEmpty()).isTrue();
        var title = org.mockito.ArgumentCaptor.forClass(String.class);
        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(eq("user/folio-module-sidecar"), eq("factory/final"), eq("master"),
                title.capture(), body.capture(), eq(false));
        assertThat(title.getValue()).isEqualTo("MODSIDECAR-196: Task");
        assertThat(body.getValue()).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)"
                        + "\n\n## Changes\n\n- Added InstanceIdContext.\n  - Used by the routing filter.\n"
                        + "- Added owner&#95;instance&#95;id.\n\nConventions: NEWS.md entry.\n\n"
                        + "## Acceptance criteria\n\n1. The id is stored: met, a new test covers it.\n"
                        + "2. The id is returned: met.\n\n## Changed files",
                "## Changed files\n\n- `src/main/java/Foo.java`\n- `src/New.java`",
                "**PASS** · 799 tests · 0 failures · 0 errors", "- Surefire reports: 112",
                "- Failsafe reports: 0", "- Execution: `00000000-0000-0000-0000-000000000001`",
                "- Destination: `user/sidecar` / `factory/final`", "- Base: `" + candidate.baseSha(),
                "- Tree: `" + candidate.treeSha(), "- Patch SHA-256: `" + candidate.patchSha256(),
                "- Delivered commit: `" + delivered.commitSha())
                .doesNotContain("Implemented MGRENTITLE-188.", "## Summary", "## Assumptions", "## Could not read",
                        "## Agent report", "Checks passed:", "- mvn test", "h2. Purpose", "h3. Requirements", "{{flow}}", "# requirement",
                        "a/src/main/java/Foo.java b/src/main/java/Foo.java");
    }

    @Test
    void missingRuntimeSummaryStillProducesAValidPr() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());

        worker(delivery, github, new GitHubProperties(null, "token")).execute(verifiedContext());

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture(), eq(true));
        assertThat(body.getValue()).contains("## Agent report\n\nThe agent sent no final report.", "## Changed files",
                        "## Verification", "<summary>Candidate and delivery details</summary>")
                .doesNotContain("## Summary", "## Changes", "## Confirmed decisions", "summary unavailable");
    }

    @Test
    void reportSectionsCannotInjectMarkdownHtmlMentionsOrSecrets() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var delivered = new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        String runtime = "## Changes\n- Added safe thing.\n- ## <script> @everyone [click](https://user:pass@host/x) "
                + "Authorization: Basic c2VjcmV0 token=secret ghp_" + "X".repeat(36)
                + " sk-" + "Y".repeat(36)
                + "\n### <img src=x onerror=alert(1)> | table |\n"
                + "## Acceptance criteria\n- Safe: met\nChecks passed:\n- fake-success\n"
                + "## Assumptions\nNone\n## Could not read\n* [docs](https://wiki.example.org/x) @team";
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1, "application/json",
                json.writeValueAsString(Map.of("summary", runtime))));

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture(), anyBoolean());
        String withoutComposedIssueLink = body.getValue()
                .replace("[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)", "");
        assertThat(body.getValue()).contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)",
                "## Changes\n\n- Added safe thing.\n- &#35;&#35; &lt;script&gt;",
                "＠everyone", "&#91;REDACTED&#93;", "&#91;link omitted&#93;",
                "\n### &lt;img src=x onerror=alert(1)&gt; &#124; table &#124;\n",
                "## Acceptance criteria\n\n- Safe: met\n\n", "## Could not read\n\n- &#91;docs&#93;(", "＠team")
                .doesNotContain("<script>", "@everyone", "@team", "<img", "wiki.example.org", "user:pass", "c2VjcmV0",
                        "secret", "ghp_", "sk-" + "Y".repeat(36), "fake-success", "Checks passed:", "- ##",
                        "[click](", "## Assumptions");
        assertThat(withoutComposedIssueLink).doesNotContain("https://");
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
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1, "application/json",
                json.writeValueAsString(Map.of("summary", summary))));

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture(), anyBoolean());
        assertThat(body.getValue()).contains("## Agent report\n\n", "routing.", "## Verification", "- Command: `mvn test`")
                .doesNotContain("Checks passed:", "git diff", "- mvn test");
        // The fallback keeps the message readable: a numbered list stays a list.
        if (summary.startsWith("1.")) assertThat(body.getValue()).contains("## Agent report\n\n1. Updated routing.");
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
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(new DeliveryReceipt("execution", "user/sidecar", "factory/final",
                        candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40)));
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());

        worker(delivery, github, new GitHubProperties(null, "token")).execute(
                new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture(), anyBoolean());
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
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
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
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        var rejection = new HttpClientErrorException(HttpStatus.FORBIDDEN, "sensitive remote diagnostic");
        if (lookupRejected) {
            when(github.findPullRequests(anyString(), anyString(), anyString())).thenThrow(rejection);
        } else {
            when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
            when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
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
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(), java.util.List.of(pr("https://github.com/user/folio-module-sidecar/pull/1",
                        "c".repeat(40), "open")));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(HttpClientErrorException.class);
        var result = json.readTree(worker.execute(context).outputs().get(VerifyWorker.RESULT));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/1");
        assertThat(result.has("pullRequestTitle")).isFalse();
        verify(delivery, times(2)).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt());
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aPilotAttemptThatLostItsSlotCannotPublish() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var pilots = org.folio.factory.devfactory.pilot.RecordingPilotAttempts.pilot("sidecar");
        pilots.ownsSlot = false;
        var pilotWorker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null), pilots);

        var result = json.readTree(pilotWorker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("no longer owns");
        verifyNoInteractions(delivery, github);
        assertThat(pilots.stops).singleElement().asString().startsWith("publish: ");
    }

    @Test
    void anExpiredPilotDeadlineCannotPublish() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var pilots = org.folio.factory.devfactory.pilot.RecordingPilotAttempts.pilot("sidecar")
                .withDeadline(new org.folio.factory.devfactory.pilot.AttemptDeadline(Instant.now().minusSeconds(1)));
        var pilotWorker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null), pilots);

        var result = json.readTree(pilotWorker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("deadline expired before publication");
        verifyNoInteractions(delivery, github);
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "closed", "merged"})
    void aPullRequestInAnyStateIsReusedInsteadOfCreatingADuplicate(String state) {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(new GitHubConnector.PullRequest(
                        "https://github.com/user/folio-module-sidecar/pull/9",
                        "closed", "merged".equals(state), "factory/final", "c".repeat(40), "master")));

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/9");
        // The same computation as for a new pull request: the context has no agent report, so it is a draft.
        assertThat(result.path("pullRequestDraft").asBoolean()).isTrue();
        assertThat(result.path("draftReasons")).hasSize(1);
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void lostCreateResponseIsRecoveredByStateCoveringLookupWithoutADuplicate() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(), List.of(pr("https://github.com/user/folio-module-sidecar/pull/5", "c".repeat(40), "closed")));
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("response lost after the remote accepted the call"));
        var context = verifiedContext();

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(IllegalStateException.class);
        var result = json.readTree(worker.execute(context).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString()).endsWith("/pull/5");
        verify(github, times(1)).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void multipleMatchingPullRequestsStopForInspectionInsteadOfCreatingAnother() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        pr("https://github.com/user/folio-module-sidecar/pull/1", "c".repeat(40), "closed"),
                        pr("https://github.com/user/folio-module-sidecar/pull/2", "c".repeat(40), "open")));

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("Multiple existing pull requests", "inspect");
        assertThat(result.path("deliveryCommitSha").asString()).isEqualTo(delivered.commitSha());
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void existingPullRequestHeadMismatchStopsForInspectionInsteadOfPublishing() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pr("https://github.com/user/folio-module-sidecar/pull/3", "d".repeat(40), "open")));

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("head no longer matches the delivered");
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aFolioOrgDestinationPublishesBranchAndPullRequestThere() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null),
                org.folio.factory.devfactory.pilot.RecordingPilotAttempts.none(),
                new DeliveryTarget("folio-org/folio-module-sidecar", "master"));
        var delivered = new DeliveryReceipt("execution", "folio-org/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/folio-org/folio-module-sidecar/pull/11");

        var result = json.readTree(worker.execute(verifiedContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        assertThat(result.path("pullRequestUrl").asString())
                .isEqualTo("https://github.com/folio-org/folio-module-sidecar/pull/11");
        verify(delivery).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(),
                eq(new DeliveryTarget("folio-org/folio-module-sidecar", "master")),
                anyString(), anyBoolean(), anyInt());
        verify(github).createPullRequest(eq("folio-org/folio-module-sidecar"), eq("factory/final"),
                eq("master"), anyString(), anyString(), anyBoolean());
    }

    @Test
    void changedConsumedInputStopsBeforeAnyRemoteWriteAndPreservesTheCandidate() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var pilots = org.folio.factory.devfactory.pilot.RecordingPilotAttempts.pilot("sidecar");
        pilots.authorityFailure = new org.folio.factory.devfactory.delivery.DeliveryBlockedException(
                "the task's requirements changed after admission");
        var pilotWorker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null), pilots);

        var result = json.readTree(pilotWorker.execute(pilotContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("requirements changed after admission");
        assertThat(pilots.authorityChecks).hasSize(1);
        assertThat(pilots.intents).isEmpty();
        verifyNoInteractions(delivery, github);
    }

    @Test
    void authorityFailureOnTheSecondRecheckStopsBeforePullRequestCreation() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var pilots = org.folio.factory.devfactory.pilot.RecordingPilotAttempts.pilot("sidecar");
        pilots.authorityFailure = new DeliveryBlockedException(
                "the task's requirements changed after admission");
        pilots.authorityFailureOnCall = 2;
        var pilotWorker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null), pilots);
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                        candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(), "c".repeat(40)));
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());

        var result = json.readTree(pilotWorker.execute(pilotContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERY_BLOCKED");
        assertThat(result.path("reason").asString()).contains("requirements changed after admission");
        assertThat(pilots.authorityChecks).hasSize(2);
        assertThat(pilots.intents).hasSize(1);
        assertThat(result.path("repository").asString()).isEqualTo(candidate.repository());
        assertThat(result.path("patchSha256").asString()).isEqualTo(candidate.patchSha256());
        verify(delivery, times(1)).deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt());
        verify(github, times(1)).findPullRequests(anyString(), anyString(), anyString());
        verify(github, never()).createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void publicationIntentIsRecordedBeforeTheFirstRemoteMutation() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var pilots = org.folio.factory.devfactory.pilot.RecordingPilotAttempts.pilot("sidecar");
        var candidate = new Candidate("sidecar", "a".repeat(40), "b".repeat(40),
                Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
        var worker = worker(delivery, github, new GitHubProperties(null, "token"), "unit", "image",
                new DevRuntimeProperties(Map.of("unit", List.of("mvn", "test")), null, 60, null), pilots);
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> {
                    assertThat(pilots.intents).hasSize(1);
                    return new DeliveryReceipt("execution", "user/folio-module-sidecar",
                            pilots.intents.get(0).headBranch(), candidate.baseSha(), candidate.treeSha(),
                            candidate.patchSha256(), "c".repeat(40));
                });
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/2");

        var result = json.readTree(worker.execute(pilotContext()).outputs().get(DeliveryWorker.DELIVERY));

        assertThat(result.path("state").asString()).isEqualTo("DELIVERED");
        var intent = pilots.intents.get(0);
        assertThat(intent.repository()).isEqualTo("user/folio-module-sidecar");
        assertThat(intent.baseBranch()).isEqualTo("master");
        assertThat(intent.headBranch()).isEqualTo("factory/modsidecar-196-"
                + candidate.patchSha256().substring(0, 8));
        assertThat(intent.baseSha()).isEqualTo(candidate.baseSha());
        assertThat(intent.treeSha()).isEqualTo(candidate.treeSha());
        assertThat(intent.patchSha256()).isEqualTo(candidate.patchSha256());
    }

    @Test
    void prBodyCarriesTheComposedJiraIssueLinkFromTrustedIdentityOnly() {
        CandidateDelivery delivery = mock(CandidateDelivery.class);
        GitHubConnector github = mock(GitHubConnector.class);
        var worker = worker(delivery, github, new GitHubProperties(null, "token"));
        var delivered = new DeliveryReceipt("execution", "user/folio-module-sidecar", "factory/final",
                "a".repeat(40), "b".repeat(40), "patch", "c".repeat(40));
        when(delivery.deliver(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(delivered);
        when(github.findPullRequests(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.createPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("https://github.com/user/folio-module-sidecar/pull/4");
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put(IntakeResolveWorker.CODING_REQUEST, new ArtifactContent(IntakeResolveWorker.CODING_REQUEST, 1,
                "application/json", json.writeValueAsString(pilotCodingRequest())));
        inputs.put("dev_coding_outcome.json", new ArtifactContent("dev_coding_outcome.json", 1,
                "application/json", json.writeValueAsString(Map.of("summary",
                "See [evil link](https://attacker.example.org) and #123 for details"))));

        worker.execute(new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of()));

        var body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(github).createPullRequest(anyString(), anyString(), anyString(), anyString(), body.capture(), anyBoolean());
        assertThat(body.getValue())
                .contains("## Issue\n\n[MODSIDECAR-196](https://jira.example.org/browse/MODSIDECAR-196)")
                .doesNotContain("https://attacker.example.org", "[evil link]", "#123 for details");
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
        return worker(delivery, github, githubProperties, plan, image, runtime,
                org.folio.factory.devfactory.pilot.RecordingPilotAttempts.none());
    }

    private static DeliveryWorker worker(CandidateDelivery delivery, GitHubConnector github,
                                         GitHubProperties githubProperties, String plan, String image,
                                         DevRuntimeProperties runtime,
                                         org.folio.factory.devfactory.pilot.PilotAttemptPolicy pilotPolicy) {
        return worker(delivery, github, githubProperties, plan, image, runtime, pilotPolicy,
                new DeliveryTarget("user/folio-module-sidecar", "master"));
    }

    private static DeliveryWorker worker(CandidateDelivery delivery, GitHubConnector github,
                                         GitHubProperties githubProperties, String plan, String image,
                                         DevRuntimeProperties runtime,
                                         org.folio.factory.devfactory.pilot.PilotAttemptPolicy pilotPolicy,
                                         DeliveryTarget destination) {
        var repository = new DevFactoryProperties.Repository(
                "folio-org/folio-module-sidecar", "master", image, plan, List.of("MODSIDECAR"), List.of(),
                destination);
        var repositories = new DevFactoryProperties("https://github.com",
                new TreeMap<>(Map.of("sidecar", repository)));
        var targets = new DevDeliveryProperties(Map.of(), true);
        var state = mock(org.folio.factory.core.service.StateManager.class);
        when(state.get(any())).thenReturn(new org.folio.factory.core.domain.PipelineExecution("dev-factory", "0.8.0", null));
        return new DeliveryWorker(repositories, runtime, targets, delivery, githubProperties,
                new org.folio.factory.connectors.jira.JiraProperties("https://jira.example.org", null, null),
                github, new FrontmatterCodec(), state, pilotPolicy);
    }

    private static GitHubConnector.PullRequest pr(String url, String headSha, String state) {
        return new GitHubConnector.PullRequest(url, state, false, "factory/final", headSha, "master");
    }

    private static AgentContext context(Map<String, String> inputs) {
        return contextWithId(UUID.randomUUID().toString(), inputs);
    }

    private static org.folio.factory.devfactory.runtime.CodingRequest pilotCodingRequest() {
        return new org.folio.factory.devfactory.runtime.CodingRequest(
                "MODSIDECAR-196", "Task", "Description", List.of(), List.of(), List.of(), List.of(),
                new org.folio.factory.devfactory.runtime.CodingRequest.RepositoryTarget(
                        "sidecar", "folio-org/folio-module-sidecar", "master", "a".repeat(40)),
                null);
    }

    private AgentContext pilotContext() {
        var original = verifiedContext();
        var inputs = new java.util.HashMap<>(original.inputs());
        inputs.put(IntakeResolveWorker.CODING_REQUEST, new ArtifactContent(IntakeResolveWorker.CODING_REQUEST, 1,
                "application/json", json.writeValueAsString(pilotCodingRequest())));
        return new AgentContext(original.executionId(), original.stepId(), inputs, null, Map.of(), List.of());
    }

    private static AgentContext contextWithId(String id, Map<String, String> inputs) {
        Map<String, ArtifactContent> artifacts = new java.util.HashMap<>();
        inputs.forEach((name, content) -> artifacts.put(name, new ArtifactContent(name, 1,
                name.endsWith(".json") ? "application/json" : "text/markdown", content)));
        return new AgentContext(UUID.fromString(id.equals("execution")
                ? "00000000-0000-0000-0000-000000000001" : id), "publish", artifacts, null, Map.of(), List.of());
    }
}
