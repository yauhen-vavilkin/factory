package org.folio.factory.app.pilot.presentation;

import org.folio.factory.app.web.AttemptCharge;
import org.folio.factory.app.web.AttemptUsageSummary;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Typed token accounting for one logical pilot task: the sum of every bound
 * attempt's priced usage contribution. Each execution contributes its
 * attempt-attributed totals exactly once — feedback rounds and retries are
 * separate contributions, cumulative streamed snapshots are replaced, not
 * re-added — and any incomplete contribution keeps the task total incomplete.
 */
public record PilotTaskUsage(
        long tokens,
        Map<AttemptCharge.Category, Long> tokensByCategory,
        SortedMap<String, BigDecimal> estimatesByCurrency,
        boolean complete,
        boolean provisional,
        List<String> models,
        List<String> rateSources) {

    public static PilotTaskUsage aggregate(List<AttemptUsageSummary.Totals> contributions,
                                           boolean usageMissingAfterPaidStart) {
        var categories = new EnumMap<AttemptCharge.Category, Long>(AttemptCharge.Category.class);
        var estimates = new TreeMap<String, BigDecimal>();
        var models = new LinkedHashSet<String>();
        var sources = new LinkedHashSet<String>();
        long tokens = 0;
        boolean complete = !usageMissingAfterPaidStart;
        boolean provisional = false;
        for (var totals : contributions) {
            tokens += totals.tokens();
            totals.breakdown().forEach((category, count) -> categories.merge(category, count, Long::sum));
            totals.costByCurrency().forEach(
                    (currency, amount) -> estimates.merge(currency, amount, BigDecimal::add));
            complete &= !totals.partial();
            provisional |= totals.provisional();
            models.addAll(totals.models());
            sources.addAll(totals.rateSources());
        }
        return new PilotTaskUsage(tokens, Collections.unmodifiableMap(categories),
                Collections.unmodifiableSortedMap(estimates), complete, provisional,
                List.copyOf(models), List.copyOf(sources));
    }
}
