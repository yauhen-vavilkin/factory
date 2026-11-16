package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.web.UiFormat;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import static org.folio.factory.app.tasks.presentation.TaskLabels.costSoFar;
import static org.folio.factory.app.tasks.presentation.TaskLabels.finishedCost;

/**
 * The Developer Flow spending of the Dashboard, built from the task projection, so each amount is the one the task
 * page shows. An attempt counts in the period when its run started in it, as the Dashboard counts runs.
 */
@Component
@RequiredArgsConstructor
public class SpendingRows {

    static final int MAX_ROWS = 15;
    private static final String NO_FIGURE = "—";
    /** The group of attempts whose run settings name no model: older runs and the Pi harness. */
    private static final String NO_MODEL = "Model not recorded";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TaskQuery tasks;
    private final TaskRows rows;

    /** Empty when no attempt of a task started its run at or after {@code since}. */
    public Optional<Spending> since(Instant since) {
        var period = tasks.startedSince(since).stream().filter(task -> !task.attempts().isEmpty()).toList();
        var costs = period.stream()
                .map(TaskCost::of)
                .sorted(Comparator.comparing(TaskCost::total).reversed().thenComparing(TaskCost::key))
                .toList();
        return costs.isEmpty() ? Optional.empty() : Optional.of(spending(costs, period));
    }

    private Spending spending(List<TaskCost> costs, List<TaskQuery.PeriodTask> period) {
        var shown = costs.subList(0, Math.min(MAX_ROWS, costs.size()));
        var scale = shown.getFirst().total();
        return new Spending(figures(costs), unpricedNote(costs), shown.stream().map(cost -> row(cost, scale)).toList(),
                rest(costs.subList(shown.size(), costs.size())), causes(period), models(period));
    }

    /** One row per cause of the attempts that ended without a pull request, the most expensive first. */
    private static List<Spending.Group> causes(List<TaskQuery.PeriodTask> period) {
        var byCause = new EnumMap<StopCause, List<TaskView.AttemptHistoryEntry>>(StopCause.class);
        period.stream().flatMap(task -> task.attempts().stream())
                .filter(SpendingRows::endedWithoutPullRequest)
                .forEach(attempt -> byCause.computeIfAbsent(StopCause.of(attempt.state(), attempt.reason()),
                        cause -> new ArrayList<>()).add(attempt));
        return byCause.entrySet().stream()
                .sorted(Comparator.comparing(entry -> total(money(entry.getValue())), Comparator.reverseOrder()))
                .map(entry -> group(entry.getKey().getLabel(), List.of(count(entry.getValue().size(), "run")),
                        entry.getValue()))
                .toList();
    }

    /** One row per model and effort of the period's attempts, in the words of the task page, the most spent first. */
    private static List<Spending.Group> models(List<TaskQuery.PeriodTask> period) {
        var byModel = new TreeMap<String, List<TaskView.AttemptHistoryEntry>>();
        period.stream().flatMap(task -> task.attempts().stream())
                .forEach(attempt -> byModel.computeIfAbsent(modelLabel(attempt), label -> new ArrayList<>())
                        .add(attempt));
        return byModel.entrySet().stream()
                .sorted(Comparator.comparing(entry -> total(money(entry.getValue())), Comparator.reverseOrder()))
                .map(entry -> group(entry.getKey(), modelFacts(entry.getValue()), entry.getValue()))
                .toList();
    }

    private static String modelLabel(TaskView.AttemptHistoryEntry attempt) {
        var label = TaskRunSettings.label(attempt.runSettings());
        return label == null ? NO_MODEL : label;
    }

    /**
     * "2 runs", "1 pull request", "$0.80 per run"; the average counts only attempts with a price, and is left
     * out when none has one.
     */
    private static List<String> modelFacts(List<TaskView.AttemptHistoryEntry> attempts) {
        var money = money(attempts);
        var pullRequests = attempts.stream().filter(attempt -> attempt.pullRequestUrl() != null).count();
        var priced = attempts.stream().filter(attempt -> TaskCost.cost(attempt) != null).count();
        var facts = new ArrayList<>(List.of(count(attempts.size(), "run"), count(pullRequests, "pull request")));
        if (!money.isEmpty() && priced > 0) {
            facts.add(amount(divide(money, priced)) + " per run");
        }
        return List.copyOf(facts);
    }

    private static Spending.Group group(String label, List<String> facts,
                                        List<TaskView.AttemptHistoryEntry> attempts) {
        var money = money(attempts);
        var unknown = UiFormat.money(money) == null
                && attempts.stream().anyMatch(attempt -> TaskCost.cost(attempt) == null);
        return new Spending.Group(label, facts, unknown ? UiFormat.COST_UNKNOWN : amount(money), unknown);
    }

    /** A running attempt has not stopped yet, and a withdrawn one never ran. */
    private static boolean endedWithoutPullRequest(TaskView.AttemptHistoryEntry attempt) {
        return attempt.pullRequestUrl() == null && !attempt.state().isInFlight()
                && attempt.state() != TaskAttempt.State.WITHDRAWN;
    }

    private static Map<String, BigDecimal> money(List<TaskView.AttemptHistoryEntry> attempts) {
        return attempts.stream().<Map<String, BigDecimal>>map(attempt -> attempt.usage().estimatesByCurrency())
                .reduce(Map.of(), SpendingRows::plus);
    }

    /** Spent, its two parts, and the spent amount for each task that has a pull request. */
    private static List<Spending.Figure> figures(List<TaskCost> costs) {
        var spent = sum(costs, TaskCost::all);
        var delivered = costs.stream().filter(TaskCost::delivered).count();
        return List.of(new Spending.Figure(amount(spent), "Spent"),
                new Spending.Figure(amount(sum(costs, TaskCost::pullRequest)), "Ended in a pull request"),
                new Spending.Figure(amount(sum(costs, TaskCost::other)), "Runs without a pull request"),
                new Spending.Figure(delivered == 0 ? NO_FIGURE : amount(divide(spent, delivered)),
                        "Cost per pull request"));
    }

    private Spending.Row row(TaskCost cost, BigDecimal scale) {
        var view = cost.view();
        var chip = rows.triggeredRow(view);
        var amount = UiFormat.money(cost.all());
        var unknown = amount == null && cost.unpriced() > 0;
        return new Spending.Row(view.taskId(), view.displayKey(), view.jiraUrl(), view.title(),
                count(cost.attempts(), "run"), unknown ? UiFormat.COST_UNKNOWN : amount(cost.all()), unknown,
                width(cost.pullRequest(), scale), width(cost.other(), scale),
                "Ended in a pull request: " + amount(cost.pullRequest()) + " · No pull request: "
                        + amount(cost.other()), chip.chipLabel(), chip.chipClass(), chip.chipUrl());
    }

    /** "2 runs have no price": the attempts whose cost is unknown on the task page; null when there are none. */
    private static String unpricedNote(List<TaskCost> costs) {
        var unpriced = costs.stream().mapToLong(TaskCost::unpriced).sum();
        if (unpriced == 0) {
            return null;
        }
        return unpriced == 1 ? "1 run has no price" : unpriced + " runs have no price";
    }

    /** "2 more tasks: $0.03" for the tasks without a row; null when every task has one. */
    private static String rest(List<TaskCost> costs) {
        return costs.isEmpty() ? null : count(costs.size(), "more task") + ": " + amount(sum(costs, TaskCost::all));
    }

    /** The part's share of the most expensive task, in whole percent. */
    private static int width(Map<String, BigDecimal> part, BigDecimal scale) {
        return scale.signum() == 0 ? 0 : total(part).multiply(HUNDRED).divide(scale, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private static String amount(Map<String, BigDecimal> amounts) {
        var money = UiFormat.money(amounts);
        return money == null ? UiFormat.ZERO_COST : money;
    }

    private static String count(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static Map<String, BigDecimal> sum(List<TaskCost> costs, Function<TaskCost, Map<String, BigDecimal>> part) {
        return costs.stream().map(part).reduce(Map.of(), SpendingRows::plus);
    }

    private static Map<String, BigDecimal> plus(Map<String, BigDecimal> first, Map<String, BigDecimal> second) {
        var sum = new TreeMap<>(first);
        second.forEach((currency, amount) -> sum.merge(currency, amount, BigDecimal::add));
        return sum;
    }

    private static Map<String, BigDecimal> divide(Map<String, BigDecimal> amounts, long divisor) {
        var shares = new TreeMap<String, BigDecimal>();
        amounts.forEach((currency, amount) -> shares.put(currency,
                amount.divide(BigDecimal.valueOf(divisor), 6, RoundingMode.HALF_UP)));
        return shares;
    }

    /** One number to order and scale by: every price in config/developer-models.yaml is in USD. */
    private static BigDecimal total(Map<String, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A task's attempts of the period: their amounts with and without a pull request, and how many have no price. */
    private record TaskCost(TaskView view, Map<String, BigDecimal> pullRequest, Map<String, BigDecimal> other,
                            int attempts, long unpriced, boolean delivered) {

        static TaskCost of(TaskQuery.PeriodTask task) {
            Map<String, BigDecimal> pullRequest = Map.of();
            Map<String, BigDecimal> other = Map.of();
            for (var attempt : task.attempts()) {
                var amounts = attempt.usage().estimatesByCurrency();
                if (attempt.pullRequestUrl() == null) {
                    other = plus(other, amounts);
                } else {
                    pullRequest = plus(pullRequest, amounts);
                }
            }
            var unpriced = task.attempts().stream().filter(attempt -> cost(attempt) == null).count();
            var delivered = task.attempts().stream().anyMatch(attempt -> attempt.pullRequestUrl() != null);
            return new TaskCost(task.view(), pullRequest, other, task.attempts().size(), unpriced, delivered);
        }

        /** The cost the task page shows for the attempt: so far while it runs; null when it has no price. */
        private static String cost(TaskView.AttemptHistoryEntry attempt) {
            return attempt.state().isInFlight() ? costSoFar(attempt.usage()) : finishedCost(attempt);
        }

        Map<String, BigDecimal> all() {
            return plus(pullRequest, other);
        }

        BigDecimal total() {
            return SpendingRows.total(all());
        }

        String key() {
            return view.displayKey();
        }
    }
}
