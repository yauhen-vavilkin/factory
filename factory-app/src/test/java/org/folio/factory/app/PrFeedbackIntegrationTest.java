package org.folio.factory.app;

import org.folio.factory.core.domain.*;
import org.folio.factory.core.engine.*;
import org.folio.factory.core.hitl.*;
import org.folio.factory.core.repository.*;
import org.folio.factory.core.service.*;
import org.folio.factory.devfactory.feedback.*;
import org.folio.factory.devfactory.candidate.*;
import org.folio.factory.devfactory.runtime.*;
import org.folio.factory.devfactory.verification.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.folio.factory.devfactory.feedback.FeedbackActions.Action.*;

/** Real engine, transactions, rendering and local fork Git; scripted coding/check boundaries. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.ai.model.chat=none","factory.engine.enabled=false"})
@Import(StubLlmConfiguration.class)
@Testcontainers
@DirtiesContext
class PrFeedbackIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16");
    static final Path root=CandidateFreezer.temporary("factory-pr-test-");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("factory.dev-factory.git-base-url",()->root.toUri().toString());
        r.add("factory.dev-factory.repositories.fixture.source-repo",()->"upstream/repo");
        r.add("factory.dev-factory.repositories.fixture.base-branch",()->"main");
        r.add("factory.dev-factory.repositories.fixture.jira-projects[0]",()->"TEST");
        r.add("factory.dev-factory.repositories.fixture.build-image",()->"fixture-image");
        r.add("factory.dev-factory.repositories.fixture.verification-plan",()->"fixture-checks");
        r.add("factory.dev-factory.runtime.plans.fixture-checks[0]",()->"fixture-check");
        r.add("factory.dev-factory.delivery.targets.fixture.repository",()->"fork/repo");
        r.add("factory.dev-factory.delivery.targets.fixture.base-branch",()->"main");
        r.add("factory.dev-factory.delivery.targets.fixture.authorized",()->"true");
        for(String model:List.of("fixture-model","second-fixture-model")) {
            String prefix="factory.developer-pricing.rates."+model+".";
            r.add(prefix+"model",()->model); r.add(prefix+"currency",()->"USD");
            r.add(prefix+"as-of",()->"2026-09-23"); r.add(prefix+"source",()->"https://example.org/fixture-pricing");
            r.add(prefix+"input-per-million",()->model.equals("fixture-model")?"2":"4");
            r.add(prefix+"output-per-million",()->model.equals("fixture-model")?"10":"20");
        }
    }
    @Autowired StateManager state;
    @Autowired PipelineExecutionRepository executions;
    @Autowired ArtifactStore artifacts;
    @Autowired AuditLog audit;
    @Autowired ExecutionEngine engine;
    @Autowired ExecutionClaimService claims;
    @Autowired ResumableStepTransactions resumable;
    @Autowired AgentLeaseHeartbeat heartbeat;
    @Autowired org.folio.factory.core.registry.FlowRegistry flows;
    @Autowired FeedbackActions actions;
    @Autowired HitlDecisionService decisions;
    @Autowired HitlGateOpener gates;
    @LocalServerPort int port;
    @MockitoBean PrFeedbackGateway gateway;
    @MockitoBean DockerWorkloads docker;
    @MockitoBean CodingRuntime coding;
    @MockitoBean CandidateVerifier verifier;
    Path bare, work, codingSource;
    UUID id;
    String branch, base;
    int calls;
    boolean noChanges, ask, checksFail, cleanupFails, exportFails, closed, merged, mergeDuringCode, statusFails, badPolicy;
    String commentsRevision="1";
    Map<String,Object> usage=Map.of("model","fixture-model","inputTokens",100L,"cacheReadTokens",0L,"cacheWriteTokens",0L,"outputTokens",20L);
    @BeforeEach void setup() throws Exception {
        calls=0; noChanges=ask=checksFail=cleanupFails=exportFails=closed=merged=mergeDuringCode=statusFails=badPolicy=false; commentsRevision="1";
        bare=root.resolve("fork/repo.git"); CandidateFreezer.delete(bare);
        work=Files.createTempDirectory(root,"work-");
        git(root,"init","--bare",bare.toString()); git(root,"init","-b","main",work.toString());
        Files.writeString(work.resolve("value.txt"),"initial\n"); commit("initial fork-only commit"); base=git(work,"rev-parse","HEAD").strip();
        id=state.createExecution("dev-factory",VERSION,null).getId(); branch="factory/test-1-"+id;
        git(work,"push",bare.toString(),"HEAD:refs/heads/"+branch);
        when(gateway.read(eq("fork/repo"),eq(7))).thenAnswer(i->pr());
        when(gateway.status(any())).thenAnswer(i->{
            if(statusFails) throw new IllegalStateException("GitHub unavailable");
            return merged ? PrFeedbackGateway.Status.MERGED : closed ? PrFeedbackGateway.Status.CLOSED : PrFeedbackGateway.Status.OPEN;
        });
        when(gateway.comments(any())).thenAnswer(i->new PrFeedbackGateway.Snapshot(pr().headSha()+commentsRevision,pr(),List.of(comment())));
        when(coding.image()).thenReturn("fixture-image"); when(coding.identity()).thenReturn(Map.of("model","fixture-model","runtime","scripted"));
        when(coding.redact(anyString())).thenAnswer(i->i.getArgument(0));
        var scope=mock(DockerWorkloads.StepScope.class); var workload=mock(DockerWorkloads.Workload.class);
        when(docker.beginStep(any(),anyString())).thenReturn(scope);
        when(scope.createSeeded(anyString(),any(),anyString(),anyString())).thenAnswer(i->{codingSource=i.getArgument(1);return workload;});
        doAnswer(i->{ if(exportFails) throw new IllegalStateException("Export unavailable");
            Path target=i.getArgument(0); try(var paths=Files.walk(codingSource)) {for(Path p:paths.toList()) {
                var relative=codingSource.relativize(p); if(relative.toString().isEmpty() || relative.startsWith(".git"))continue;
                var out=target.resolve(relative); if(Files.isDirectory(p)) Files.createDirectories(out); else Files.copy(p,out,StandardCopyOption.REPLACE_EXISTING);
            }} return null;
        }).when(workload).export(any());
        doAnswer(i->{if(cleanupFails && calls>0) throw new IllegalStateException("Resource still exists");return null;}).when(docker).cleanOwned(any(),anyString());
        when(coding.code(any(),any(),anyInt(),any())).thenAnswer(i->{
            calls++;
            if(mergeDuringCode) merged=true;
            if(!noChanges) Files.writeString(codingSource.resolve("value.txt"),Files.readString(codingSource.resolve("value.txt"))+"fix "+calls+"\n");
            Consumer<Map<String,Object>> progress=i.getArgument(3); var streamed=new HashMap<String,Object>(usage); streamed.put("activity","coding_usage"); progress.accept(streamed);
            if(ask) return CodingOutcome.needsDecision(new CodingOutcome.Decision(CodingOutcome.DecisionKind.PRODUCT_REQUIREMENTS,"Keep both options?",List.of("Yes","No"),"Partial patch saved"),usage);
            return CodingOutcome.completed("Corrected selected feedback",usage);
        });
        when(verifier.verify(any(),anyString(),any(),anyString())).thenAnswer(i->{
            Candidate c=i.getArgument(2); Instant now=Instant.now();
            return new VerificationReceipt(id.toString(),c.repository(),c.baseSha(),c.treeSha(),c.patchSha256(),badPolicy?"previous-checks":"fixture-checks","fixture-image",List.of("fixture-check"),"scripted-checks",now,now,checksFail?1:0,1,1,checksFail?1:0,0,checksFail?"FAIL":"PASS","Independent fixture report",checksFail?VerificationFailure.CANDIDATE:VerificationFailure.NONE,0,List.of(),List.of());
        });
        var request=new CodingRequest("TEST-1","Fixture task","Fix behavior",List.of(),List.of(),List.of("Checks pass"),List.of(),
                new CodingRequest.RepositoryTarget("fixture","upstream/repo","main",base),CodingRequest.Constraints.defaults());
        artifacts.putMarkdown(id,"dev_coding_request.json",JSON.writeValueAsString(request),"fixture");
        artifacts.putMarkdown(id,"dev_delivery.json",JSON.writeValueAsString(Map.of("state","DELIVERED","repository","fixture","deliveryBranch",branch,"pullRequestUrl","https://github.com/fork/repo/pull/7")),"fixture");
        var e=state.get(id); e.setCurrentStepIndex(8); executions.save(e);
    }
    @AfterEach void finish() { cleanupFails=false; if(!state.get(id).getStatus().isFinal()) state.transition(id,ExecutionStatus.CANCELLED,Map.of()); }
    @AfterAll static void clean() { CandidateFreezer.cleanup(root); }
    PrFeedbackGateway.PullRequest pr() {return new PrFeedbackGateway.PullRequest("fork/repo",7,branch,"main",git(bare,"rev-parse",branch).strip(),"https://github.com/fork/repo/pull/7",!closed);}
    PrFeedbackGateway.Comment comment() {return new PrFeedbackGateway.Comment("inline:1","Reviewer","Fix selected behavior","value.txt",1,false,commentsRevision,"https://github.com/fork/repo/pull/7#discussion_r1");}
    void drive() {
        for(int n=0;n<20;n++) {var e=state.get(id); if(e.getStatus()==ExecutionStatus.AWAITING_HITL || e.getStatus().isFinal()) return;
            claims.claim(100).forEach(engine::advance);
        }
        fail("Execution failed to settle: "+state.get(id).getStatus()+" "+state.get(id).getErrorMessage());
    }
    FeedbackActions.View view() {return actions.view(id);}
    void action(FeedbackActions.Action action) {var v=view(); actions.command(id,new FeedbackActions.Command(v.reviewId(),v.version(),action,v.snapshot()==null?null:v.snapshot().id(),List.of("inline:1"),"Keep both"),"operator");}
    void select() {actions.refresh(id,view().version()); action(FIX);}
    void startRound() {drive(); select(); drive();}
    @Test void twelveRoundsUseOneExecutionOneBranchAndRenderChronologicalHistory() throws Exception {
        drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY);
        verifyNoInteractions(verifier); verify(coding,never()).code(any(),any(),anyInt(),any());
        for(int round=1;round<=12;round++) {
            String model=round%2==0?"second-fixture-model":"fixture-model";
            when(coding.identity()).thenReturn(Map.of("model",model,"runtime","scripted"));
            usage=Map.of("model",model,"inputTokens",100L,"cacheReadTokens",0L,"cacheWriteTokens",0L,"outputTokens",20L);
            select(); drive(); assertThat(view().state().phase()).as("round %s reason %s",round,view().state().reason()).isEqualTo(FeedbackState.Phase.READY);
            assertThat(view().state().round()).isEqualTo(round); assertThat(pr().headSha()).isEqualTo(view().state().commitSha());
        }
        assertThat(calls).isEqualTo(12); assertThat(git(bare,"rev-list","--count",branch).strip()).isEqualTo("13");
        assertThat(git(bare,"for-each-ref","--format=%(refname)","refs/heads").lines()).containsExactly("refs/heads/"+branch);
        var html=RestClient.create("http://localhost:"+port).get().uri("/executions/"+id).retrieve().body(String.class);
        assertThat(html).contains("Round 1","Round 12","Total cost","Factory time","fixture-model","second-fixture-model","0.0072 USD","Work on feedback");
        assertThat(html).doesNotContain("Download current patch", "Download diagnostics", "Rate-card estimate", "Finish task");
        assertThatThrownBy(()->action(FINISH)).isInstanceOf(IllegalArgumentException.class);
        merged=true; actions.reconcilePr(id); drive(); assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(view().state().summary()).contains("Verified changes published");
        assertThat(view().state().taskSummary()).isEqualTo("PR merged");
        assertThat(view().state().completionReason()).isEqualTo("MERGED");
    }
    @Test void preservesPartialPatchAcrossQuestionAndFailureThenVerifiesBeforePublishing() {
        ask=true; startRound(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.QUESTION); assertThat(actions.patch(id)).contains("fix 1");
        assertThat(pr().headSha()).isEqualTo(base); verifyNoInteractions(verifier);
        ask=false; checksFail=true; action(ANSWER); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.FAILURE);
        assertThat(actions.patch(id)).contains("fix 1","fix 2"); assertThat(pr().headSha()).isEqualTo(base);
        checksFail=false; action(RETRY_CHECKS); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY); assertThat(calls).isEqualTo(2);
    }
    @Test void rejectsOldSelectionsDuplicateCommandsAndEveryGenericDecision() {
        drive(); var v=actions.refresh(id,view().version());
        for(var d:HitlDecision.values()) assertThatThrownBy(()->decisions.decide(v.reviewId(),d,"operator",null,Map.of())).isInstanceOf(IllegalArgumentException.class);
        var command=new FeedbackActions.Command(v.reviewId(),v.version(),FIX,v.snapshot().id(),List.of("inline:1"),null);
        actions.command(id,command,"operator"); assertThatThrownBy(()->actions.command(id,command,"operator")).isInstanceOf(IllegalStateException.class);
        commentsRevision="2"; drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.REFRESH_REQUIRED); assertThat(calls).isZero();
        assertThatThrownBy(()->actions.command(id,command,"operator")).isInstanceOf(IllegalStateException.class);
    }
    @Test void cleanupFailureBlocksAllActionsUntilExplicitCleanup() {
        cleanupFails=true; startRound(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.CLEANUP_BLOCKED);
        assertThat(view().actions()).containsExactly(RETRY_CLEANUP); assertThatThrownBy(()->action(FINISH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->actions.refresh(id,view().version())).isInstanceOf(IllegalStateException.class);
        assertThat(pr().headSha()).isEqualTo(base); assertThat(calls).isEqualTo(1);
        cleanupFails=false; action(RETRY_CLEANUP); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY); assertThat(calls).isEqualTo(1);
    }
    @Test void noChangesProducesVerificationWithoutEmptyCommitAndClosedPrCancels() {
        noChanges=true; startRound(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY);
        assertThat(pr().headSha()).isEqualTo(base); verify(verifier).verify(eq(id),anyString(),any(),contains("fork/repo.git"));
        closed=true; actions.reconcilePr(id);
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(view().state().completionReason()).isEqualTo("CLOSED_WITHOUT_MERGE");
    }
    @Test void failedExportReportsLostEditsKeepsPreviousSnapshotAndUsage() {
        exportFails=true; startRound(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.FAILURE);
        assertThat(view().state().reason()).contains("Latest edits could not be saved"); assertThat(actions.patch(id)).isEmpty();
        assertThat(audit.forExecution(id)).anyMatch(e->e.getEventType()==AuditEventType.STEP_COMPLETED && e.getDetail().contains("inputTokens"));
    }
    @Test void oldExecutionSkipsFeedback() throws Exception {
        var oldId=state.createExecution("dev-factory","0.8.0",null).getId();
        for(String name:List.of("dev_coding_request.json","dev_delivery.json"))
            artifacts.putMarkdown(oldId,name,artifacts.getLatest(id,name).orElseThrow().getContent(),"fixture");
        state.transition(id,ExecutionStatus.CANCELLED,Map.of()); id=oldId;
        var e=state.get(id); e.setCurrentStepIndex(8); executions.save(e); drive();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED); verifyNoInteractions(gateway);
    }
    @Test void runWithoutPublishedPrKeepsPreviousCompletionBehaviorWithoutNeedingDocker() {
        artifacts.putMarkdown(id,"dev_delivery.json","{}","fixture"); drive();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED); verifyNoInteractions(docker,gateway,verifier);
    }
    @Test void outdatedVerificationPolicyBlocksPushUntilCurrentChecksPass() {
        badPolicy=true; startRound(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.FAILURE);
        assertThat(view().state().reason()).contains("Verification policy changed"); assertThat(view().actions()).contains(RETRY_CHECKS);
        assertThat(pr().headSha()).isEqualTo(base);
        badPolicy=false; action(RETRY_CHECKS); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY);
        assertThat(calls).isEqualTo(1);
    }
    @Test void typedHttpActionsRejectReplayAndPageReloadNeverStartsAnotherAttempt() {
        drive(); var rest=RestClient.create("http://localhost:"+port); var v=view();
        var refreshed=rest.post().uri("/api/executions/"+id+"/pr-feedback/refresh")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("stateVersion",v.version()))
                .retrieve().body(FeedbackActions.View.class);
        var command=new FeedbackActions.Command(refreshed.reviewId(),refreshed.version(),FIX,refreshed.snapshot().id(),List.of("inline:1"),null);
        assertThat(rest.post().uri("/api/executions/"+id+"/pr-feedback/actions").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(command).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(202);
        assertThatThrownBy(()->rest.post().uri("/api/executions/"+id+"/pr-feedback/actions").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(command).retrieve().toBodilessEntity()).isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
        drive();
        for(int i=0;i<2;i++) rest.get().uri("/executions/"+id).retrieve().toBodilessEntity();
        assertThat(calls).isEqualTo(1);
    }
    @Test void allUnconfirmedOperationsAreCleanedAfterRepeatedCrashes() {
        drive(); UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        audit.record(id,AuditEventType.STEP_STARTED,STEP,Map.of("invocationId",first.toString()));
        audit.record(id,AuditEventType.STEP_STARTED,STEP,Map.of("invocationId",second.toString()));
        merged=true; actions.reconcilePr(id); drive();
        verify(docker).cleanOwned(id,"pr-feedback-"+first); verify(docker).cleanOwned(id,"pr-feedback-"+second);
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
    @Test void replayedCodingOperationWaitsForHumanAndDoesNotSpendAgain() {
        drive(); select(); claims.claim(100).forEach(engine::advance);
        assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.CODE);
        claims.claim(100); var e=state.get(id); var step=flows.require("dev-factory").step(9);
        var inputs=new LinkedHashMap<String,org.folio.factory.core.agent.ArtifactContent>();
        for(String name:step.inputs()) {
            var a=artifacts.getLatest(id,name).orElseThrow();
            inputs.put(name,new org.folio.factory.core.agent.ArtifactContent(name,a.getVersion(),a.getContentType(),a.getContent()));
        }
        try(var lease=heartbeat.start(e)) {
            lease.checked(version->resumable.start(id,9,version,step,UUID.randomUUID(),inputs));
        }
        state.scheduleRetry(id,0,"Simulated process restart after durable start"); drive();
        assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.INTERRUPTED); assertThat(calls).isZero();
        action(RETRY_CODE); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY); assertThat(calls).isEqualTo(1);
    }
    @Test void manualBranchUpdatesAreCheckedWithoutCallingTheAgentOrAddingACommit() throws Exception {
        drive(); Files.writeString(work.resolve("manual.txt"),"Human correction\n"); commit("manual correction");
        git(work,"push",bare.toString(),"HEAD:refs/heads/"+branch); String manual=pr().headSha();
        action(MANUAL_CONTINUE); drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.READY);
        assertThat(pr().headSha()).isEqualTo(manual); assertThat(calls).isZero();
        assertThat(view().state().verification().baseSha()).isEqualTo(manual);
        String html=RestClient.create("http://localhost:"+port).get().uri("/executions/"+id).retrieve().body(String.class);
        assertThat(html).contains("Manual verification 1").doesNotContain("Round 1");
    }
    @Test void unexpectedEngineEscalationRequiresCleanupAndNeverAutomaticallyRepeatsCoding() {
        drive(); select(); claims.claim(100).forEach(engine::advance);
        gates.openEscalationReview(state.get(id),flows.require("dev-factory").step(9),"Unexpected persistence error",3);
        assertThat(view().actions()).containsExactly(RETRY_CLEANUP);
        action(RETRY_CLEANUP); drive();
        assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.INTERRUPTED); assertThat(calls).isZero();
    }
    @Test void branchChangeBeforeCodingRequiresFreshSelection() throws Exception {
        drive(); select(); claims.claim(100).forEach(engine::advance);
        Files.writeString(work.resolve("manual.txt"),"Human correction\n"); commit("manual correction");
        git(work,"push",bare.toString(),"HEAD:refs/heads/"+branch);
        drive(); assertThat(view().state().phase()).isEqualTo(FeedbackState.Phase.REFRESH_REQUIRED); assertThat(calls).isZero();
    }
    @Test void mergeBetweenSelectionAndWorkerStartSkipsPaidCallAndCompletes() {
        drive(); select(); merged=true; drive();
        assertThat(calls).isZero();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(view().state().completionReason()).isEqualTo("MERGED");
        String html=RestClient.create("http://localhost:"+port).get().uri("/executions/"+id).retrieve().body(String.class);
        assertThat(html).doesNotContain("Round 1");
    }
    @Test void mergeBetweenCommentRefreshAndActionRejectsWithoutCreatingRound() {
        drive(); actions.refresh(id,view().version()); merged=true;
        assertThatThrownBy(()->action(FIX)).hasMessageContaining("no longer open");
        assertThat(view().state().round()).isZero();
        assertThat(calls).isZero();
        actions.reconcilePr(id); drive();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
    @Test void mergeDuringCodingFinishesCurrentCallAndSkipsVerificationAndPublication() {
        mergeDuringCode=true; drive(); select(); drive();
        assertThat(calls).isEqualTo(1);
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(pr().headSha()).isEqualTo(base);
        verifyNoInteractions(verifier);
    }
    @Test void closedBetweenSelectionAndWorkerStartSkipsPaidCallAndCancels() {
        drive(); select(); closed=true; drive();
        assertThat(calls).isZero();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
        actions.reconcilePr(id);
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        String html=RestClient.create("http://localhost:"+port).get().uri("/executions/"+id).retrieve().body(String.class);
        assertThat(html).doesNotContain("Round 1");
    }
    @Test void githubErrorLeavesReviewPendingAndRetryCanMerge() {
        drive(); statusFails=true;
        assertThatThrownBy(()->actions.reconcilePr(id)).hasMessageContaining("GitHub unavailable");
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
        statusFails=false; merged=true; actions.reconcilePr(id); drive();
        assertThat(state.get(id).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
    void commit(String message) {git(work,"add",".");git(work,"-c","user.name=Fixture","-c","user.email=fixture@example.org","commit","-m",message);}
    static String git(Path p,String...args) {return CandidateFreezer.git(p,args);}
}
