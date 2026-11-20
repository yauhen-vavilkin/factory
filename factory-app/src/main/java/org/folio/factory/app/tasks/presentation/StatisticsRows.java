package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.admission.TaskRunSettings;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.web.FeedbackUi;
import org.folio.factory.app.web.UiFormat;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.folio.factory.app.tasks.presentation.TaskLabels.costSoFar;
import static org.folio.factory.app.tasks.presentation.TaskLabels.finishedCost;

/**
 * The Statistics tab of the Developer Flow and its line on the Dashboard, built from the task projection, so each
 * amount is the one the task page shows. A run counts in the period when it started in it, as the Dashboard counts
 * runs, and its task counts with it; a withdrawn run never ran and does not count.
 */
@Component
@RequiredArgsConstructor
public class StatisticsRows {

    private static final String NO_FIGURE = "—";
    /** The group of runs whose run settings name no model: older runs and the Pi harness. */
    private static final String NO_MODEL = "Model not recorded";

    private final TaskQuery tasks;
    private final FeedbackUi feedback;

    /** Empty when no run started at or after {@code since}. */
    public Optional<Statistics> since(Instant since) {
        var period = counted(since);
        if (period.isEmpty()) {
            return Optional.empty();
        }
        var costs = period.stream().map(this::taskCost).toList();
        return Optional.of(new Statistics(tasksPart(costs), runsPart(period), moneyPart(costs), models(period)));
    }

    /**
     * "$1.90" and "1 run has no price": what the runs that started at or after {@code since} cost, with the note
     * the Statistics tab shows for them; empty when none started.
     */
    public Optional<Statistics.Spent> spentSince(Instant since) {
        var attempts = counted(since).stream().flatMap(task -> task.attempts().stream()).toList();
        return attempts.isEmpty() ? Optional.empty()
                : Optional.of(new Statistics.Spent(amount(money(attempts)), unpricedNote(unpriced(attempts))));
    }

    /** The tasks of the period with their runs, the same entries as their view, so each run finds its chip. */
    private List<TaskQuery.PeriodTask> counted(Instant since) {
        return tasks.startedSince(since).stream()
                .map(task -> new TaskQuery.PeriodTask(task.view(), task.attempts().stream()
                        .filter(attempt -> attempt.state() != TaskAttempt.State.WITHDRAWN).toList()))
                .filter(task -> !task.attempts().isEmpty())
                .toList();
    }

    /** The bucket of the task reads where its PR feedback stands, which takes a few reads per task. */
    private TaskCost taskCost(TaskQuery.PeriodTask task) {
        var result = TaskResult.of(task.view(), feedback.standing(task.view()));
        return new TaskCost(result, money(task.attempts()), unpriced(task.attempts()));
    }

    /** "7 of 8" reached a pull request, merged, without one; one bar of every bucket. */
    private static Statistics.Tasks tasksPart(List<TaskCost> costs) {
        var counts = costs.stream().collect(Collectors.groupingBy(TaskCost::result,
                () -> new EnumMap<>(TaskResult.class), Collectors.counting()));
        long total = costs.size();
        var reached = costs.stream().filter(cost -> cost.result().reachedPullRequest()).count();
        var figures = List.of(new Statistics.Figure(reached + " of " + total, "Reached a pull request"),
                figure(counts.getOrDefault(TaskResult.MERGED, 0L), TaskResult.MERGED.getLabel()),
                figure(counts.getOrDefault(TaskResult.NO_PULL_REQUEST, 0L), TaskResult.NO_PULL_REQUEST.getLabel()));
        var buckets = Arrays.stream(TaskResult.values())
                .filter(result -> result.isAlwaysShown() || counts.containsKey(result))
                .map(result -> new Statistics.Bucket(result.key(), counts.getOrDefault(result, 0L), result.getLabel()))
                .toList();
        var barLabel = "Of " + count(total, "task") + ": " + buckets.stream()
                .map(bucket -> bucket.count() + " " + bucket.label().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        return new Statistics.Tasks(total, figures, buckets, barLabel);
    }

    /** One figure per way a run ended, then the runs per task with one decimal. */
    private static Statistics.Runs runsPart(List<TaskQuery.PeriodTask> period) {
        var counts = new EnumMap<RunResult, Long>(RunResult.class);
        period.forEach(task -> task.attempts().forEach(attempt -> RunResult.of(task.view(), attempt)
                .ifPresent(result -> counts.merge(result, 1L, Long::sum))));
        var total = counts.values().stream().mapToLong(Long::longValue).sum();
        var figures = new ArrayList<Statistics.Figure>();
        Arrays.stream(RunResult.values())
                .filter(result -> result.isAlwaysShown() || counts.containsKey(result))
                .forEach(result -> figures.add(figure(counts.getOrDefault(result, 0L), result.getLabel())));
        var perTask = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(period.size()), 1, RoundingMode.HALF_UP);
        figures.add(new Statistics.Figure(perTask.toPlainString(), "Runs per task"));
        return new Statistics.Runs(total, List.copyOf(figures));
    }

    /** Spent, and the average spend of all tasks, of those that reached a pull request and of the merged ones. */
    private static Statistics.Money moneyPart(List<TaskCost> costs) {
        var figures = List.of(new Statistics.Figure(amount(sum(costs)), "Spent"),
                new Statistics.Figure(average(costs, cost -> true), "Per task"),
                new Statistics.Figure(average(costs, cost -> cost.result().reachedPullRequest()),
                        "Per task with a pull request"),
                new Statistics.Figure(average(costs, cost -> cost.result() == TaskResult.MERGED), "Per merged task"));
        return new Statistics.Money(figures, unpricedNote(costs.stream().mapToLong(TaskCost::unpriced).sum()));
    }

    /** What the matching tasks spent, divided by their count; "—" when no task matches. */
    private static String average(List<TaskCost> costs, Predicate<TaskCost> matches) {
        var matching = costs.stream().filter(matches).toList();
        return matching.isEmpty() ? NO_FIGURE : amount(divide(sum(matching), matching.size()));
    }

    /** One row per model and effort of the period's runs, in the words of the task page, the most spent first. */
    private static List<Statistics.ModelRow> models(List<TaskQuery.PeriodTask> period) {
        var byModel = new TreeMap<String, List<TaskView.AttemptHistoryEntry>>();
        period.stream().flatMap(task -> task.attempts().stream())
                .forEach(attempt -> byModel.computeIfAbsent(modelLabel(attempt), label -> new ArrayList<>())
                        .add(attempt));
        return byModel.entrySet().stream()
                .sorted(Comparator.comparing(entry -> total(money(entry.getValue())), Comparator.reverseOrder()))
                .map(entry -> modelRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String modelLabel(TaskView.AttemptHistoryEntry attempt) {
        var label = TaskRunSettings.label(attempt.runSettings());
        return label == null ? NO_MODEL : label;
    }

    /** The average counts only runs with a price, and is "—" when none has one. */
    private static Statistics.ModelRow modelRow(String label, List<TaskView.AttemptHistoryEntry> attempts) {
        var money = money(attempts);
        var pullRequests = attempts.stream().filter(RunResult::opensPullRequest).count();
        var priced = attempts.stream().filter(attempt -> cost(attempt) != null).count();
        var perRun = money.isEmpty() || priced == 0 ? NO_FIGURE : amount(divide(money, priced));
        var unknown = UiFormat.money(money) == null && priced < attempts.size();
        return new Statistics.ModelRow(label, UiFormat.count(attempts.size()), UiFormat.count(pullRequests), perRun,
                unknown ? UiFormat.COST_UNKNOWN : amount(money), unknown);
    }

    /** The cost the task page shows for the run: so far while it runs; null when it has no price. */
    private static String cost(TaskView.AttemptHistoryEntry attempt) {
        return attempt.state().isInFlight() ? costSoFar(attempt.usage()) : finishedCost(attempt);
    }

    /** The runs whose cost is unknown on the task page. */
    private static long unpriced(List<TaskView.AttemptHistoryEntry> attempts) {
        return attempts.stream().filter(attempt -> cost(attempt) == null).count();
    }

    /** "2 runs have no price": the note on the {@link #unpriced} runs; null when there are none. */
    private static String unpricedNote(long unpriced) {
        if (unpriced == 0) {
            return null;
        }
        return unpriced == 1 ? "1 run has no price" : unpriced + " runs have no price";
    }

    private static Statistics.Figure figure(long count, String label) {
        return new Statistics.Figure(UiFormat.count(count), label);
    }

    private static String amount(Map<String, BigDecimal> amounts) {
        var money = UiFormat.money(amounts);
        return money == null ? UiFormat.ZERO_COST : money;
    }

    private static String count(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static Map<String, BigDecimal> money(List<TaskView.AttemptHistoryEntry> attempts) {
        return attempts.stream().<Map<String, BigDecimal>>map(attempt -> attempt.usage().estimatesByCurrency())
                .reduce(Map.of(), StatisticsRows::plus);
    }

    private static Map<String, BigDecimal> sum(List<TaskCost> costs) {
        return costs.stream().map(TaskCost::money).reduce(Map.of(), StatisticsRows::plus);
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

    /** One number to order by: every price in config/developer-models.yaml is in USD. */
    private static BigDecimal total(Map<String, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A task of the period: its bucket, what its runs of the period cost, and how many of them have no price. */
    private record TaskCost(TaskResult result, Map<String, BigDecimal> money, long unpriced) {
    }
}
