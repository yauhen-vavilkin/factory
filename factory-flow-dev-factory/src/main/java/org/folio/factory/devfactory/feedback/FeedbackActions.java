package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.domain.*;
import org.folio.factory.core.hitl.*;
import org.folio.factory.core.repository.*;
import org.folio.factory.core.service.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;
import static org.folio.factory.devfactory.feedback.FeedbackState.Phase.*;

/** Network reads happen before short, version-checked command transactions. */
public class FeedbackActions {
    public enum Action { FIX, ANSWER, RETRY_CODE, RETRY_CHECKS, RETRY_OPERATION, MANUAL_CONTINUE, RETRY_CLEANUP, FINISH }
    public record Command(UUID reviewId, int stateVersion, Action action, String snapshotId,
                          List<String> selectedIds, String answer) { }
    public record View(FeedbackState state, int version, UUID reviewId, List<Action> actions, PrFeedbackGateway.Snapshot snapshot) { }
    private final ArtifactStore artifacts;
    private final PipelineExecutionRepository executions;
    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisions;
    private final FeedbackDecisionPolicy policy;
    private final PrFeedbackGateway github;
    private final TransactionTemplate transactions;
    public FeedbackActions(ArtifactStore artifacts, PipelineExecutionRepository executions, HitlReviewRepository reviews,
            HitlDecisionService decisions, FeedbackDecisionPolicy policy, PrFeedbackGateway github, PlatformTransactionManager tx) {
        this.artifacts=artifacts; this.executions=executions; this.reviews=reviews; this.decisions=decisions;
        this.policy=policy; this.github=github; this.transactions=new TransactionTemplate(tx);
    }
    public View view(UUID id) {
        var e=executions.findById(id).orElseThrow();
        if (!e.getFlowId().equals("dev-factory")) throw new IllegalArgumentException("Not a Developer Flow");
        var artifact=artifacts.getLatest(id,STATE).orElseThrow();
        var s=JSON.readValue(artifact.getContent(),FeedbackState.class);
        var pending=reviews.findByExecutionIdOrderByCreatedAtAsc(id).stream()
                .filter(r->r.getStatus()==HitlReviewStatus.PENDING && r.getStepIndex()==e.getCurrentStepIndex()).findFirst();
        boolean ready=e.getStatus()==ExecutionStatus.AWAITING_HITL || e.getStatus()==ExecutionStatus.FAILED_ESCALATED;
        var snapshot=artifacts.getLatest(id,SNAPSHOT).map(a->JSON.readValue(a.getContent(),PrFeedbackGateway.Snapshot.class)).orElse(null);
        return new View(s,artifact.getVersion(),pending.map(HitlReview::getId).orElse(null),
                ready && pending.isPresent() ? e.getStatus()==ExecutionStatus.FAILED_ESCALATED
                        ? List.of(Action.RETRY_CLEANUP) : allowed(s) : List.of(),snapshot);
    }
    public View refresh(UUID id, int expectedVersion) {
        var before=view(id);
        if (before.version()!=expectedVersion || before.reviewId()==null || before.actions().isEmpty()
                || before.actions().contains(Action.RETRY_CLEANUP))
            throw new IllegalStateException("Feedback state changed; reload");
        if (before.state().pr==null) throw new IllegalStateException("No delivered PR is available");
        var snapshot=github.comments(before.state().pr);
        transactions.executeWithoutResult(status->{
            lock(id); var current=view(id);
            if (current.version()!=expectedVersion || !Objects.equals(current.reviewId(),before.reviewId()))
                throw new IllegalStateException("Feedback state changed while reading comments");
            artifacts.putMarkdown(id,SNAPSHOT,JSON.writeValueAsString(snapshot),"pr-feedback:refresh");
        });
        return view(id);
    }
    public void command(UUID id, Command command, String reviewer) {
        if (command.action()==Action.FINISH)
            throw new IllegalArgumentException("An open PR cannot be finished; Factory completes after merge");
        if (requiresOpenPr(command.action())) {
            var before=view(id);
            if (before.state().pr==null || github.status(before.state().pr)!=PrFeedbackGateway.Status.OPEN)
                throw new IllegalStateException("PR is no longer open; reload the execution");
        }
        transactions.executeWithoutResult(status->{
            lock(id); var current=view(id);
            if (command.action()==null || current.version()!=command.stateVersion()
                    || !Objects.equals(current.reviewId(),command.reviewId()) || current.reviewId()==null)
                throw new IllegalStateException("Feedback action is stale; reload the execution");
            if (!current.actions().contains(command.action())) throw new IllegalArgumentException("Action is not available in this state");
            var s=current.state();
            switch(command.action()) {
                case FIX -> {
                    var snapshot=current.snapshot();
                    if (snapshot==null || !snapshot.id().equals(command.snapshotId()) || !snapshot.pr().open())
                        throw new IllegalArgumentException("Refresh the open PR before selecting feedback");
                    var ids=command.selectedIds()==null?Set.<String>of():new LinkedHashSet<>(command.selectedIds());
                    var selected=snapshot.comments().stream().filter(c->ids.contains(c.id())).toList();
                    if (ids.isEmpty() || selected.size()!=ids.size()) throw new IllegalArgumentException("Select current comments");
                    s.round++; s.selected=selected; s.snapshotId=snapshot.id(); s.pr=snapshot.pr(); s.manual=false;
                    s.phase=PREPARE; attempt(s); s.roundStartedAt=s.startedAt;
                }
                case ANSWER -> {
                    if (command.answer()==null || command.answer().isBlank() || command.answer().length()>16000)
                        throw new IllegalArgumentException("A bounded, nonempty answer is required");
                    s.answer=command.answer(); s.phase=CODE; attempt(s);
                }
                case RETRY_CODE -> { s.question="Independent verification or coding failure: " + s.reason;
                    s.answer="Repair the preserved candidate using the saved diagnostics. Keep the authorized scope.";
                    s.phase=CODE; attempt(s); }
                case RETRY_CHECKS -> s.phase=VERIFY;
                case RETRY_OPERATION -> s.phase=s.resumePhase;
                case MANUAL_CONTINUE -> { s.round++; s.manual=true; s.selected=List.of(); s.phase=PREPARE; attempt(s); s.roundStartedAt=s.startedAt; }
                case RETRY_CLEANUP -> {
                    // An unexpected engine escalation must also pass strict resource reconciliation.
                    if(s.phase!=CLEANUP_BLOCKED) {
                        s.cleanupResumePhase=s.phase==CODE?INTERRUPTED:s.phase; s.phase=CLEANUP_BLOCKED;
                    }
                }
                case FINISH -> throw new IllegalArgumentException("Finish task is no longer available");
            }
            policy.authorize(current.reviewId(),()->decisions.decide(current.reviewId(),HitlDecision.APPROVE,
                    reviewer,command.action().name(),null));
            artifacts.putMarkdown(id,STATE,JSON.writeValueAsString(s),"pr-feedback:"+command.action().name());
        });
    }
    /** Called by the bounded PR monitor after a trusted GitHub status read. */
    public void reconcilePr(UUID id) {
        var execution=executions.findById(id).orElseThrow();
        if (execution.getStatus()!=ExecutionStatus.AWAITING_HITL || !VERSION.equals(execution.getFlowVersion())) return;
        var before=view(id);
        if (before.reviewId()==null || before.state().pr==null || before.state().phase==CLEANUP_BLOCKED) return;
        var review=reviews.findById(before.reviewId()).orElseThrow();
        if (!"pr-feedback".equals(review.getGateId())) return;
        var observed=github.status(before.state().pr);
        if (observed==null) throw new IllegalStateException("GitHub returned an unknown PR status");
        if (observed==PrFeedbackGateway.Status.OPEN) return;
        transactions.executeWithoutResult(tx->{
            lock(id);
            var current=view(id);
            if (executions.findById(id).orElseThrow().getStatus()!=ExecutionStatus.AWAITING_HITL
                    || current.version()!=before.version() || !Objects.equals(current.reviewId(),before.reviewId())
                    || current.state().phase==CLEANUP_BLOCKED) return;
            var s=current.state();
            if (observed==PrFeedbackGateway.Status.MERGED) {
                s.phase=FINISH; s.completionReason="MERGED"; s.reason="";
                policy.authorize(current.reviewId(),HitlDecision.APPROVE,()->decisions.decide(
                        current.reviewId(),HitlDecision.APPROVE,"factory-pr-monitor","PR merged",null));
            } else {
                s.phase=STOPPED; s.completionReason="CLOSED_WITHOUT_MERGE";
                s.taskSummary="PR closed without merge"; s.reason=s.taskSummary;
                policy.authorize(current.reviewId(),HitlDecision.REJECT,()->decisions.decide(
                        current.reviewId(),HitlDecision.REJECT,"factory-pr-monitor",s.taskSummary,null));
            }
            artifacts.putMarkdown(id,STATE,JSON.writeValueAsString(s),"pr-feedback:reconcile-pr");
        });
    }
    public String patch(UUID id) {
        view(id);
        return artifacts.getLatest(id,CANDIDATE).map(a->JSON.readTree(a.getContent()).path("patch").asString("")).orElse("");
    }
    public String diagnostics(UUID id) {
        var current=view(id);
        return current.state().reason + "\n\n" + artifacts.getLatest(id,VERIFICATION)
                .map(a->JSON.readTree(a.getContent()).path("output").asString("")).orElse("");
    }
    private void lock(UUID id) { executions.lockById(id).orElseThrow(); }
    private static boolean requiresOpenPr(Action action) {
        return action!=null && action!=Action.RETRY_CLEANUP && action!=Action.FINISH;
    }
    private static void attempt(FeedbackState s) {
        s.attemptId=UUID.randomUUID().toString(); s.startedAt=Instant.now().toString(); s.finishedAt="";
    }
    public static List<Action> allowed(FeedbackState s) {
        if (s.phase==CLEANUP_BLOCKED) return List.of(Action.RETRY_CLEANUP);
        if (!s.waiting()) return List.of();
        var actions=new ArrayList<Action>();
        if (s.pr!=null) { actions.add(Action.FIX); actions.add(Action.MANUAL_CONTINUE); }
        if (s.phase==QUESTION && !"REPOSITORY_MISMATCH".equals(s.failureKind)) actions.add(Action.ANSWER);
        if (s.phase==INTERRUPTED || s.phase==FAILURE && Set.of("CODING","CANDIDATE","CANDIDATE_COMPILE").contains(s.failureKind))
            actions.add(Action.RETRY_CODE);
        if (s.phase==FAILURE && !Set.of("CODING","OPERATION").contains(s.failureKind)) actions.add(Action.RETRY_CHECKS);
        if (s.phase==FAILURE && s.resumePhase!=null && s.resumePhase!=CODE && s.resumePhase!=FINISH)
            actions.add(Action.RETRY_OPERATION);
        return List.copyOf(actions);
    }
}
