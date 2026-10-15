package org.folio.factory.app.web;
import org.folio.factory.core.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AttemptUsageSummaryTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    AuditEvent event(String id,String activity,boolean end,String model,String currency,long input,long output) {
        var d=new LinkedHashMap<String,Object>(); d.put("attemptId",id);d.put("activity",activity);d.put("model",model);d.put("round",1);
        d.put("inputTokens",input);d.put("outputTokens",output);d.put("cacheReadTokens",0);d.put("cacheWriteTokens",0);
        d.put("rate",Map.of("currency",currency,"inputPerMillion",2,"outputPerMillion",10));
        return new AuditEvent(UUID.randomUUID(),end?AuditEventType.STEP_COMPLETED:AuditEventType.RUNTIME_PROGRESS,"pr-follow-up","test",JSON.writeValueAsString(d));
    }
    @Test void finalReplacesStreamsAndSeparateAttemptsAndModelsAreSummed() {
        var events=List.of(event("1","coding_starting",false,"a","USD",0,0),event("1","coding_usage",false,"a","USD",50,20),
                event("1","",true,"a","USD",100,30),event("1","coding_usage",false,"a","USD",150,50),
                event("2","",true,"b","USD",200,40));
        var result=AttemptUsageSummary.build(events,null);
        assertThat(result).containsEntry("attempts",2).containsEntry("tokens",370L).containsEntry("cost","0.0013 USD")
                .containsEntry("partial",false).containsEntry("models","a, b");
    }
    @Test void interruptedUsageIsIncludedAndCurrenciesStaySeparate() {
        var result=AttemptUsageSummary.build(List.of(event("1","",true,"a","USD",100,0),event("2","",true,"b","EUR",100,0),
                event("3","coding_usage",false,"c","USD",200,20)),null);
        assertThat(result).containsEntry("cost","0.0002 EUR + 0.0008 USD").containsEntry("partial",true)
                .containsEntry("tokensDisplay","420");
        assertThat(AttemptUsageSummary.build(List.of(event("4","coding_starting",false,"d","",0,0)),null))
                .containsEntry("cost","—").containsEntry("partial",true).containsEntry("tokensDisplay","—");
        assertThat(AttemptUsageSummary.build(List.of(event("5","",true,"a","USD",0,0)),null)).containsEntry("cost","0 USD");
    }
    @Test void waitingAfterAbandonedAttemptIsNotCountedAsActiveWork() {
        Instant start=Instant.parse("2026-09-23T12:00:00Z");
        var started=new AuditEvent(UUID.randomUUID(),AuditEventType.STEP_STARTED,"pr-follow-up","test","{}");
        var wait=new AuditEvent(started.getExecutionId(),AuditEventType.HITL_REQUESTED,"pr-follow-up","test","{}");
        ReflectionTestUtils.setField(started,"occurredAt",start); ReflectionTestUtils.setField(wait,"occurredAt",start.plusSeconds(5));
        assertThat(AttemptUsageSummary.activeSeconds(List.of(started,wait),start.plusSeconds(86400))).isEqualTo(5);
    }
    @Test void runningAttemptShowsEstimateFromStreamAndCapturedRate() {
        var events=List.of(event("live","coding_starting",false,"a","USD",0,0),
                event("live","coding_usage",false,"a","USD",100,20));
        assertThat(AttemptUsageSummary.build(events,null)).containsEntry("cost","0.0004 USD");
        assertThat(AttemptUsageSummary.build(events,null,true))
                .containsEntry("cost","0.0004 USD")
                .containsEntry("tokensDisplay","120")
                .containsEntry("provisional",true);
    }
    @Test void incompleteUsageKeepsPricedSubtotalAndReportedTokens() {
        var incomplete=new AuditEvent(UUID.randomUUID(),AuditEventType.RUNTIME_PROGRESS,"implement","test","""
                {"attemptId":"partial","activity":"coding_usage","inputTokens":100,"outputTokens":20,
                 "usageIncomplete":true,"rate":{"currency":"USD","inputPerMillion":2}}
                """);
        assertThat(AttemptUsageSummary.build(List.of(incomplete),null,true))
                .containsEntry("cost","0.0002 USD").containsEntry("tokensDisplay","120").containsEntry("partial",true);
        var unpriced=new AuditEvent(UUID.randomUUID(),AuditEventType.RUNTIME_PROGRESS,"implement","test","""
                {"attemptId":"unpriced","activity":"coding_usage","inputTokens":100,"outputTokens":0,
                 "cacheReadTokens":0,"cacheWriteTokens":0,"rate":{"currency":"USD"}}
                """);
        assertThat(AttemptUsageSummary.build(List.of(unpriced),null))
                .containsEntry("cost","—").containsEntry("tokensDisplay","100").containsEntry("partial",true);
    }
    @Test void restartedAttemptDoesNotEraseEarlierSpendAndRoundFilterIsPreserved() {
        var events=List.of(event("old","coding_usage",false,"a","USD",100,20),
                event("new","coding_starting",false,"a","USD",0,0),
                event("new","coding_usage",false,"a","USD",50,10));
        assertThat(AttemptUsageSummary.build(events,1,true))
                .containsEntry("cost","0.0006 USD").containsEntry("tokensDisplay","180");
        assertThat(AttemptUsageSummary.build(events,-1,true))
                .containsEntry("cost","0").containsEntry("tokensDisplay","0").containsEntry("present",false);
    }
    @Test void projectionKeepsRoundsSeparateAndUsesCapturedRates() {
        var start = event("initial", "coding_starting", false, "a", "USD", 0, 0);
        var initial = event("initial", "", true, "a", "USD", 100, 20);
        var initialDetail = (tools.jackson.databind.node.ObjectNode) JSON.readTree(initial.getDetail());
        initialDetail.put("round", -1);
        initialDetail.set("rate", JSON.readTree("{\"currency\":\"EUR\",\"inputPerMillion\":999,\"outputPerMillion\":999}"));
        var startDetail = (tools.jackson.databind.node.ObjectNode) JSON.readTree(start.getDetail());
        startDetail.put("round", -1);
        var events = List.of(
                new AuditEvent(start.getExecutionId(), start.getEventType(), "implement", "test", JSON.writeValueAsString(startDetail)),
                new AuditEvent(initial.getExecutionId(), initial.getEventType(), "implement", "test", JSON.writeValueAsString(initialDetail)),
                event("round", "", true, "b", "EUR", 200, 30));
        var projection = new AttemptUsageSummary.Projection(events);
        assertThat(projection.summary(-1, false)).containsEntry("cost", "0.0004 USD").containsEntry("attempts", 1);
        assertThat(projection.summary(1, false)).containsEntry("cost", "0.0007 EUR").containsEntry("tokens", 230L);
        assertThat(projection.summary(null, false)).containsEntry("cost", "0.0007 EUR + 0.0004 USD")
                .containsEntry("tokens", 350L).containsEntry("attempts", 2);
    }
}
