package org.folio.factory.app.web;
import org.folio.factory.core.domain.*;
import org.folio.factory.devfactory.feedback.*;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import java.time.*;
import java.util.*;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

@Component
public class FeedbackUi {
    private final FeedbackActions actions;
    public FeedbackUi(FeedbackActions actions) { this.actions=actions; }
    public void populate(PipelineExecution execution, List<Artifact> artifacts, List<AuditEvent> events, Model model) {
        if(VERSION.equals(execution.getFlowVersion())) {
            boolean live=execution.getStatus()==ExecutionStatus.RUNNING;
            var usage=AttemptUsageSummary.build(events,null,live);
            var initial=AttemptUsageSummary.build(events,-1,live);
            model.addAttribute("attemptUsage",usage);
            model.addAttribute("initialUsage",initial);
            var totals=new LinkedHashMap<String,Object>();
            totals.put("present",initial.get("present")); totals.put("coding",true);
            totals.put("total",initial.get("tokensDisplay"));
            ((Map<?,?>)initial.get("breakdown")).forEach((k,v)->totals.put(k.toString(),UiFormat.count(((Number)v).longValue())));
            model.addAttribute("tokenTotals",totals); model.addAttribute("estimatedCost",null);
            model.addAttribute("usageIncomplete",initial.get("partial"));
        }
        if(!VERSION.equals(execution.getFlowVersion()) || artifacts.stream().noneMatch(a->a.getName().equals(STATE))) return;
        var view=actions.view(execution.getId());
        if(view.state().phase==FeedbackState.Phase.SKIPPED) return;
        var data=new LinkedHashMap<String,Object>();
        data.put("version",view.version()); data.put("reviewId",view.reviewId());
        data.put("phase",view.state().phase==FeedbackState.Phase.FINISHED
                && !"MERGED".equals(view.state().completionReason) ? "Task finished" : label(view.state().phase));
        data.put("reason",view.state().phase==FeedbackState.Phase.FINISHED ? view.state().taskSummary : view.state().reason);
        data.put("refresh",view.reviewId()!=null && view.state().pr!=null && !view.actions().isEmpty()
                && !view.actions().contains(FeedbackActions.Action.RETRY_CLEANUP));
        data.put("fix",view.actions().contains(FeedbackActions.Action.FIX));
        data.put("answer",view.actions().contains(FeedbackActions.Action.ANSWER));
        data.put("snapshotId",view.snapshot()==null?"":view.snapshot().id());
        var processed=new HashSet<String>();
        var rounds=new TreeMap<Integer,FeedbackState>();
        var workedRounds=new HashSet<Integer>();
        for(var a:artifacts.stream().filter(a->a.getName().equals(STATE)).sorted(Comparator.comparingInt(Artifact::getVersion)).toList()) {
            var s=JSON.readValue(a.getContent(),FeedbackState.class);
            if(s.round>0 && (Set.of(FeedbackState.Phase.CODE, FeedbackState.Phase.VERIFY,
                    FeedbackState.Phase.PREPARE_PUBLISH, FeedbackState.Phase.PUBLISH,
                    FeedbackState.Phase.QUESTION).contains(s.phase)
                    || Set.of("CODING", "VERIFICATION", "PUBLICATION").contains(Objects.toString(s.failureKind,""))))
                workedRounds.add(s.round);
            if(s.round>0 && s.phase!=FeedbackState.Phase.FINISH && s.phase!=FeedbackState.Phase.FINISHED
                    && s.phase!=FeedbackState.Phase.STOPPED) rounds.put(s.round,s);
            if(!s.commitSha.isBlank() || s.phase==FeedbackState.Phase.READY && s.verification!=null && "PASS".equals(s.verification.result())) s.selected.forEach(c->processed.add(c.id()));
        }
        rounds.keySet().retainAll(workedRounds);
        var comments=new ArrayList<Map<String,Object>>();
        if(view.snapshot()!=null) for(var c:view.snapshot().comments()) {
            var row=new LinkedHashMap<String,Object>();
            row.put("id",c.id()); row.put("author",c.author()); row.put("body",c.body());
            row.put("location",c.path().isBlank()?"PR discussion":c.path()+(c.line()==null?"":":"+c.line()));
            row.put("outdated",c.outdated()); row.put("processed",processed.contains(c.id())); comments.add(row);
        }
        data.put("comments",comments);
        var buttons=new ArrayList<Map<String,String>>();
        for(var action:view.actions()) if(action!=FeedbackActions.Action.FIX && action!=FeedbackActions.Action.ANSWER)
            buttons.add(Map.of("value",action.name(),"label",actionLabel(action)));
        data.put("actions",buttons);
        var cards=new ArrayList<Map<String,Object>>();
        int correction=0, manual=0;
        for(var entry:rounds.entrySet()) {
            var s=entry.getValue(); var card=new LinkedHashMap<String,Object>();
            card.put("round",entry.getKey()); card.put("open",entry.getKey().equals(rounds.lastKey()));
            card.put("title",s.manual?"Manual verification "+(++manual):"Round "+(++correction));
            card.put("manual",s.manual);
            card.put("phase",s.phase==FeedbackState.Phase.READY ? (!s.commitSha.isBlank()?"Changes published":s.manual?"Manual changes verified":"No changes needed") : label(s.phase)); card.put("summary",s.summary); card.put("reason",s.reason); card.put("commit",s.commitSha);
            card.put("selected",s.selected.stream().map(c->Map.of("author",c.author(),"body",c.body())).toList());
            card.put("usage",AttemptUsageSummary.build(events,entry.getKey(),execution.getStatus()==ExecutionStatus.RUNNING));
            card.put("runtime",s.runtimeName); card.put("verification",s.verification);
            var activity=new ArrayList<Map<String,String>>();
            for(var event:events) {
                var d=JSON.readTree(event.getDetail()==null?"{}":event.getDetail());
                if(d.path("round").asInt(-1)!=entry.getKey()) continue;
                String text=d.path("message").asString(d.path("activity").asString(""));
                if(text.isBlank() || text.equals("coding_usage") || text.equals("pi_usage")) continue;
                activity.add(Map.of("time",UiFormat.format(event.getOccurredAt()),"text",text));
            }
            card.put("activity",activity); cards.add(card);
        }
        data.put("rounds",cards);
        model.addAttribute("feedback",data);
    }
    static String duration(long s) { return s/3600+"h "+s%3600/60+"m "+s%60+"s"; }
    static String label(FeedbackState.Phase p) { return switch(p) {
        case READY->"Awaiting PR feedback"; case PREPARE->"Preparing selected feedback"; case CODE->"Implementing corrections";
        case VERIFY->"Running independent checks"; case PREPARE_PUBLISH->"Preparing commit"; case PUBLISH->"Updating PR";
        case QUESTION->"Your answer is needed"; case FAILURE->"Action needed"; case REFRESH_REQUIRED->"Refresh PR and select comments again";
        case INTERRUPTED->"Coding attempt interrupted"; case CLEANUP_BLOCKED->"Resource cleanup required";
        case FINISHED->"PR merged"; case STOPPED->"PR closed without merge"; default->p.name();
    }; }
    static String actionLabel(FeedbackActions.Action a) { return switch(a) {
        case FIX->"Fix selected comments"; case ANSWER->"Answer and continue"; case RETRY_CODE->"Try fixing with the agent";
        case RETRY_CHECKS->"Run checks again"; case RETRY_OPERATION->"Retry interrupted operation";
        case MANUAL_CONTINUE->"Verify my manual changes"; case RETRY_CLEANUP->"Retry cleanup"; case FINISH->"Finish task";
    }; }
}
