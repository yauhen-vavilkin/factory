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
    static AttemptUsageSummary.Totals totals(List<AuditEvent> events,Integer round,boolean live) {
        return new AttemptUsageSummary.Projection(events).totals(round,live);
    }
    @Test void finalReplacesStreamsAndSeparateAttemptsAndModelsAreSummed() {
        var events=List.of(event("1","coding_starting",false,"a","USD",0,0),event("1","coding_usage",false,"a","USD",50,20),
                event("1","",true,"a","USD",100,30),event("1","coding_usage",false,"a","USD",150,50),
                event("2","",true,"b","USD",200,40));
        var result=totals(events,null,false);
        assertThat(result.attempts()).isEqualTo(2); assertThat(result.tokens()).isEqualTo(370);
        assertThat(result.costLabel()).isEqualTo("<$0.01"); assertThat(result.partial()).isFalse();
        assertThat(result.models()).containsExactly("a","b");
    }
    @Test void interruptedUsageIsIncludedAndCurrenciesStaySeparate() {
        var result=totals(List.of(event("1","",true,"a","USD",100,0),event("2","",true,"b","EUR",100,0),
                event("3","coding_usage",false,"c","USD",200,20)),null,false);
        assertThat(result.costLabel()).isEqualTo("<0.01 EUR + <$0.01"); assertThat(result.partial()).isTrue();
        assertThat(result.tokens()).isEqualTo(420);
        var unreported=totals(List.of(event("4","coding_starting",false,"d","",0,0)),null,false);
        assertThat(unreported.costLabel()).isEqualTo("—"); assertThat(unreported.partial()).isTrue();
        assertThat(unreported.present()).isTrue(); assertThat(unreported.tokensReported()).isFalse();
        assertThat(totals(List.of(event("5","",true,"a","USD",0,0)),null,false).costLabel()).isEqualTo("$0.00");
        // While the attempt works and has reported nothing yet, it has cost nothing so far.
        assertThat(totals(List.of(event("6","coding_starting",false,"d","USD",0,0)),null,true).costLabel())
                .isEqualTo("$0.00");
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
        assertThat(totals(events,null,false).costLabel()).isEqualTo("<$0.01");
        assertThat(totals(events,null,true).costLabel()).isEqualTo("<$0.01");
        assertThat(totals(events,null,true).tokens()).isEqualTo(120);
        assertThat(AttemptUsageSummary.totals(events,true).provisional()).isTrue();
    }
    @Test void incompleteUsageKeepsPricedSubtotalAndReportedTokens() {
        var incomplete=new AuditEvent(UUID.randomUUID(),AuditEventType.RUNTIME_PROGRESS,"implement","test","""
                {"attemptId":"partial","activity":"coding_usage","inputTokens":100,"outputTokens":20,
                 "usageIncomplete":true,"rate":{"currency":"USD","inputPerMillion":2}}
                """);
        assertThat(totals(List.of(incomplete),null,true)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens,AttemptUsageSummary.Totals::partial).containsExactly("<$0.01",120L,true);
        var unpriced=new AuditEvent(UUID.randomUUID(),AuditEventType.RUNTIME_PROGRESS,"implement","test","""
                {"attemptId":"unpriced","activity":"coding_usage","inputTokens":100,"outputTokens":0,
                 "cacheReadTokens":0,"cacheWriteTokens":0,"rate":{"currency":"USD"}}
                """);
        assertThat(totals(List.of(unpriced),null,false)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens,AttemptUsageSummary.Totals::partial).containsExactly("—",100L,true);
    }
    @Test void restartedAttemptDoesNotEraseEarlierSpendAndRoundFilterIsPreserved() {
        var events=List.of(event("old","coding_usage",false,"a","USD",100,20),
                event("new","coding_starting",false,"a","USD",0,0),
                event("new","coding_usage",false,"a","USD",50,10));
        assertThat(totals(events,1,true)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens).containsExactly("<$0.01",180L);
        assertThat(totals(events,-1,true)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens,AttemptUsageSummary.Totals::present).containsExactly("$0.00",0L,false);
    }
    @Test void typedTotalsCountTokensAttemptsAndTheBreakdown() {
        var events=List.of(event("1","coding_starting",false,"a","USD",0,0),event("1","coding_usage",false,"a","USD",50,20),
                event("1","",true,"a","USD",100,30),event("2","",true,"b","USD",200,40));
        var totals=AttemptUsageSummary.totals(events,false);
        assertThat(totals.tokens()).isEqualTo(370);
        assertThat(totals.attempts()).isEqualTo(2);
        assertThat(totals.present()).isTrue();
        assertThat(totals.partial()).isFalse();
        assertThat(totals.tokensReported()).isTrue();
        assertThat(totals.costByCurrency().get("USD")).isEqualByComparingTo("0.0013");
        assertThat(totals.breakdown()).containsEntry(AttemptCharge.Category.INPUT,300L)
                .containsEntry(AttemptCharge.Category.OUTPUT,70L)
                .containsEntry(AttemptCharge.Category.CACHE_READ,0L)
                .containsEntry(AttemptCharge.Category.CACHE_WRITE,0L);
        var streaming=List.of(event("live","coding_starting",false,"a","USD",0,0),
                event("live","coding_usage",false,"a","USD",100,20));
        assertThat(AttemptUsageSummary.totals(streaming,true)).extracting(
                        AttemptUsageSummary.Totals::provisional, AttemptUsageSummary.Totals::present)
                .containsExactly(true,true);
        assertThat(AttemptUsageSummary.totals(streaming,false).provisional()).isFalse();
        assertThat(AttemptUsageSummary.totals(List.of(),false).present()).isFalse();
    }

    @Test void unattributedLegacyStepUsageStaysVisibleButUncounted() {
        var legacy=new AuditEvent(UUID.randomUUID(),AuditEventType.STEP_COMPLETED,"implement","test",
                "{\"inputTokens\":5000,\"outputTokens\":1000,\"model\":\"a\"}");
        var totals=AttemptUsageSummary.totals(List.of(legacy),false);
        assertThat(totals.present()).isTrue();
        assertThat(totals.partial()).isTrue();
        assertThat(totals.tokens()).isZero();
        assertThat(totals.tokensReported()).isFalse();
        assertThat(totals.attempts()).isZero();
        assertThat(totals.costByCurrency()).isEmpty();
    }

    @Test void oldAndNewAccountingPriceIdenticalUsageIdentically() {
        var estimator=DeveloperCostEstimatorTest.estimatorFor("claude-haiku-5-5",
                "https://www.anthropic.com/pricing","0.15","0.03","0.50");
        String rate="{\"currency\":\"USD\",\"source\":\"anthropic-pricing\",\"asOf\":\"2026-09-19\","
                +"\"inputPerMillion\":0.15,\"cacheReadPerMillion\":0.03,\"outputPerMillion\":0.50}";
        var initial=new AuditEvent(UUID.randomUUID(),AuditEventType.STEP_COMPLETED,"implement","test",
                "{\"attemptId\":\"h1\",\"round\":-1,\"model\":\"claude-haiku-5-5\",\"inputTokens\":100000,"
                +"\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"outputTokens\":20000,\"rate\":"+rate+"}");
        var feedback=new AuditEvent(UUID.randomUUID(),AuditEventType.STEP_COMPLETED,"implement","test",
                "{\"attemptId\":\"h2\",\"round\":1,\"model\":\"claude-haiku-5-5\",\"inputTokens\":40000,"
                +"\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"outputTokens\":4000,\"rate\":"+rate+"}");

        var legacyEstimate=StepUsageSummary.from(List.of(initial),true,JSON).estimate(estimator);
        var initialTotals=new AttemptUsageSummary.Projection(List.of(initial)).totals(-1,false);
        var withFeedbackTotals=AttemptUsageSummary.totals(List.of(initial,feedback),false);

        assertThat(legacyEstimate).isNotNull();
        assertThat(legacyEstimate.amount()).isEqualTo("0.025");
        assertThat(initialTotals.costByCurrency().get("USD")).isEqualByComparingTo(legacyEstimate.amount());
        assertThat(initialTotals.breakdown()).containsEntry(AttemptCharge.Category.INPUT,100_000L)
                .containsEntry(AttemptCharge.Category.OUTPUT,20_000L);
        // The run total adds what each part shows: $0.03 for the first implementation and the exact 0.008 of a
        // round that shows <$0.01.
        assertThat(withFeedbackTotals.costByCurrency().get("USD")).isEqualByComparingTo("0.038");
        assertThat(withFeedbackTotals.breakdown()).containsEntry(AttemptCharge.Category.INPUT,140_000L)
                .containsEntry(AttemptCharge.Category.OUTPUT,24_000L);
        assertThat(withFeedbackTotals.rateSources()).containsExactly("anthropic-pricing (2026-09-19)");
        assertThat(withFeedbackTotals.models()).containsExactly("claude-haiku-5-5");
        assertThat(withFeedbackTotals.partial()).isFalse();
        assertThat(totals(List.of(initial,feedback),1,false).costLabel()).isEqualTo("<$0.01");
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
        assertThat(projection.totals(-1, false)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::attempts).containsExactly("<$0.01", 1);
        assertThat(projection.totals(1, false)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens).containsExactly("<0.01 EUR", 230L);
        assertThat(projection.totals(null, false)).extracting(AttemptUsageSummary.Totals::costLabel,
                AttemptUsageSummary.Totals::tokens, AttemptUsageSummary.Totals::attempts)
                .containsExactly("<0.01 EUR + <$0.01", 350L, 2);
    }

    @Test void anAmountUnderOneCentIsNeverShownAsFreeAndATotalAddsWhatItsPartsShow() {
        assertThat(UiFormat.money(new java.math.BigDecimal("0.004"),"USD")).isEqualTo("<$0.01");
        assertThat(UiFormat.money(new java.math.BigDecimal("0.004"),"EUR")).isEqualTo("<0.01 EUR");
        assertThat(UiFormat.money(java.math.BigDecimal.ZERO,"USD")).isEqualTo("$0.00");
        assertThat(UiFormat.money(new java.math.BigDecimal("0.01"),"USD")).isEqualTo("$0.01");
        assertThat(runTotal("0.004","0.004","0.004")).isEqualTo("$0.01");
        assertThat(runTotal("0.005","0.005","0.005")).isEqualTo("$0.02");
        assertThat(runTotal("0.425","0.425")).isEqualTo("$0.86");
        assertThat(runTotal("0.003")).isEqualTo("<$0.01");
    }
    /** The cost of a run whose first implementation and rounds cost these amounts, one group each. */
    static String runTotal(String... amounts) {
        var events=new ArrayList<AuditEvent>();
        for (int i=0;i<amounts.length;i++) {
            var tokens=new java.math.BigDecimal(amounts[i]).movePointRight(6).longValueExact();
            events.add(new AuditEvent(UUID.randomUUID(),AuditEventType.STEP_COMPLETED,"implement","test",
                    "{\"attemptId\":\"a"+i+"\",\"round\":"+(i==0?-1:i)+",\"inputTokens\":"+tokens
                    +",\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"outputTokens\":0,"
                    +"\"rate\":{\"currency\":\"USD\",\"inputPerMillion\":1,\"outputPerMillion\":1}}"));
        }
        return UiFormat.money(new AttemptUsageSummary.Projection(events).totals(null,false).costByCurrency());
    }
}
