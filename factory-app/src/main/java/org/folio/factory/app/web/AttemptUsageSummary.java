package org.folio.factory.app.web;
import org.folio.factory.core.domain.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.*;
import java.time.*;
import java.util.*;

/** Immutable audit snapshots are grouped per actual coding attempt, never just per step. */
final class AttemptUsageSummary {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String[] TOKENS={"inputTokens","cacheReadTokens","cacheWriteTokens","outputTokens"};
    private static final String[] RATES={"inputPerMillion","cacheReadPerMillion","cacheWritePerMillion","outputPerMillion"};
    private static class Attempt {
        String model=""; JsonNode usage=JSON.createObjectNode(), rate=JSON.createObjectNode(); boolean complete;
    }
    static Map<String,Object> build(List<AuditEvent> events, Integer round) {
        return build(events,round,false);
    }
    static Map<String,Object> build(List<AuditEvent> events, Integer round, boolean live) {
        Map<String,Attempt> attempts=new LinkedHashMap<>(); boolean legacyUnknown=false;
        for(var event:events) {
            var d=JSON.readTree(event.getDetail()==null?"{}":event.getDetail());
            if(round!=null && d.path("round").asInt(-1)!=round) continue;
            String id=d.path("attemptId").asString("");
            String activity=d.path("activity").asString("");
            if(!activity.equals("coding_starting") && !activity.equals("coding_usage") && !activity.equals("pi_usage")
                    && event.getEventType()!=AuditEventType.STEP_COMPLETED) continue;
            if(event.getEventType()==AuditEventType.STEP_COMPLETED && !d.has("inputTokens") && !d.has("outputTokens")) continue;
            if(id.isBlank()) { legacyUnknown=true; continue; }
            var a=attempts.computeIfAbsent(id,k->new Attempt());
            if(a.model.isBlank() && d.hasNonNull("model")) a.model=d.path("model").asString();
            if(a.rate.isEmpty() && d.has("rate")) a.rate=d.path("rate");
            boolean terminal=event.getEventType()==AuditEventType.STEP_COMPLETED;
            if(terminal || !a.complete && (activity.equals("coding_usage") || activity.equals("pi_usage"))) a.usage=d;
            a.complete|=terminal;
        }
        var models=new LinkedHashSet<String>(); var totals=new TreeMap<String,BigDecimal>();
        var sources=new LinkedHashSet<String>();
        long tokens=0; long[] breakdown=new long[4]; boolean partial=legacyUnknown, provisional=false, tokensReported=false;
        for(var entry:attempts.entrySet()) {
            var a=entry.getValue();
            if(!a.model.isBlank()) models.add(a.model);
            String source=a.rate.path("source").asString("");
            String asOf=a.rate.path("asOf").asString("");
            if(!source.isBlank()) sources.add(asOf.isBlank()?source:source+" ("+asOf+")");
            boolean known=a.complete && !a.usage.path("usageIncomplete").asBoolean(false); long observed=0;
            boolean priced=false; int reportedCategories=0;
            provisional|=live && !a.complete;
            BigDecimal cost=BigDecimal.ZERO;
            for(int i=0;i<TOKENS.length;i++) {
                var value=a.usage.path(TOKENS[i]);
                if(!value.isNumber()) {known=false; continue;}
                tokensReported=true;
                reportedCategories++;
                long n=Math.max(0,value.asLong()); observed+=n; breakdown[i]+=n;
                if(n!=0 && !a.rate.path(RATES[i]).isNumber()) known=false;
                else if(n!=0) {
                    priced=true;
                    cost=cost.add(BigDecimal.valueOf(n).multiply(a.rate.path(RATES[i]).decimalValue()).divide(BigDecimal.valueOf(1000000)));
                }
            }
            tokens+=observed;
            String currency=a.rate.path("currency").asString("");
            known &= !currency.isBlank();
            // Usage snapshots are cumulative per attempt. Interrupted attempts still incurred
            // their observed cost; missing data must not erase the priced subtotal.
            if((priced || reportedCategories==TOKENS.length && observed==0) && !currency.isBlank()) totals.merge(currency,cost,BigDecimal::add);
            if(!known) partial=true;
        }
        String cost=totals.entrySet().stream().map(e->e.getValue().setScale(6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" "+e.getKey())
                .collect(java.util.stream.Collectors.joining(" + "));
        if(cost.isBlank()) cost=attempts.isEmpty() && !legacyUnknown?"0":"—";
        String tokensDisplay=tokensReported || attempts.isEmpty() && !legacyUnknown ? UiFormat.count(tokens) : "—";
        return Map.of("present",!attempts.isEmpty() || legacyUnknown,"cost",cost,"partial",partial,"tokens",tokens,
                "tokensDisplay",tokensDisplay,
                "provisional",provisional,
                "models",String.join(", ",models),"attempts",attempts.size(),
                "sources",List.copyOf(sources),
                "breakdown",Map.of("input",breakdown[0],"cacheRead",breakdown[1],"cacheWrite",breakdown[2],"output",breakdown[3]));
    }
    static long activeSeconds(List<AuditEvent> events, Instant now) {
        Instant start=null; long millis=0;
        for(var e:events) {
            // Only one step is active per execution. HITL and recovery end an abandoned interval.
            if(e.getEventType()==AuditEventType.STEP_STARTED) {
                if(start!=null) millis+=Math.max(0,Duration.between(start,e.getOccurredAt()).toMillis());
                start=e.getOccurredAt();
            }
            boolean stop=e.getEventType()==AuditEventType.STEP_COMPLETED || e.getEventType()==AuditEventType.STEP_FAILED
                    || e.getEventType()==AuditEventType.HITL_REQUESTED || e.getEventType()==AuditEventType.EXECUTION_COMPLETED;
            if(e.getEventType()==AuditEventType.STATE_TRANSITION) {
                String to=JSON.readTree(e.getDetail()==null?"{}":e.getDetail()).path("to").asString("");
                stop|=!to.equals("RUNNING");
            }
            if(stop && start!=null) {millis+=Math.max(0,Duration.between(start,e.getOccurredAt()).toMillis());start=null;}
        }
        if(start!=null) millis+=Math.max(0,Duration.between(start,now).toMillis());
        return millis/1000;
    }
}
