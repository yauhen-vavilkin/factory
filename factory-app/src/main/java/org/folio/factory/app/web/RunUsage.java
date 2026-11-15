package org.folio.factory.app.web;

import org.folio.factory.app.web.DeveloperRunView.Figure;
import org.folio.factory.core.domain.AuditEvent;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The money of a run page: the Usage column and the amount of the status card, from the attempt usage of the
 * current flow version or the step usage of an older one.
 */
record RunUsage(DeveloperRunView.Usage column, String amount, boolean partial, List<String> pricing) {

    static final String INCOMPLETE = "Some usage was not reported, so the real cost may be higher.";
    static final String NO_PRICE = "No price is set for this model, so the cost is not known.";

    /**
     * A run with PR feedback rounds titles its column "First implementation", since the status card shows the
     * total of the implementation and the rounds.
     */
    static RunUsage attempts(AttemptUsageSummary.Projection projection, boolean live, boolean rounds) {
        var first = projection.totals(-1, live);
        var all = projection.totals(null, live);
        var figures = new ArrayList<Figure>();
        for (var category : AttemptCharge.Category.values()) {
            var count = first.breakdown().getOrDefault(category, 0L);
            if (count > 0) {
                figures.add(new Figure(category.label(), UiFormat.count(count)));
            }
        }
        var total = !first.present() ? null : first.tokensReported() ? UiFormat.count(first.tokens()) : "—";
        var column = new DeveloperRunView.Usage(rounds ? "First implementation" : "Usage", List.copyOf(figures),
                total, first.costLabel(), first.partial() ? INCOMPLETE : null);
        return new RunUsage(column, all.present() ? all.costLabel() : null, all.partial(), all.rateSources());
    }

    /** Runs of older flow versions have no attempt identities, so their usage is summed per step. */
    static RunUsage steps(List<AuditEvent> events, JsonMapper json,
                          DeveloperCostEstimator estimator) {
        var usage = StepUsageSummary.from(events, true, json);
        var totals = usage.tokenTotals(true);
        var estimate = usage.estimate(estimator);
        var present = Boolean.TRUE.equals(totals.get("present"));
        var figures = stepFigures(totals);
        var cost = estimate == null ? null : estimate.label();
        var note = usage.incomplete() ? INCOMPLETE : present && cost == null ? NO_PRICE : null;
        var column = new DeveloperRunView.Usage("Usage", figures,
                present ? totals.get("total").toString() : null, cost, note);
        return new RunUsage(column, cost, usage.incomplete(),
                estimate == null ? List.of() : List.of(estimate.source() + " (" + estimate.asOf() + ")"));
    }

    /** Coding token counts, or the prompt and completion counts of runs from before coding usage was reported. */
    private static List<Figure> stepFigures(Map<String, Object> totals) {
        if (!Boolean.TRUE.equals(totals.get("present"))) {
            return List.of();
        }
        if (!Boolean.TRUE.equals(totals.get("coding"))) {
            return List.of(new Figure("Prompt", totals.get("prompt").toString()),
                    new Figure("Completion", totals.get("completion").toString()));
        }
        var figures = new ArrayList<Figure>();
        for (var category : AttemptCharge.Category.values()) {
            var value = totals.get(category.key());
            if (value != null) {
                figures.add(new Figure(category.label(), value.toString()));
            }
        }
        return List.copyOf(figures);
    }
}
