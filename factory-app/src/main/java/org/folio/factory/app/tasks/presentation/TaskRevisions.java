package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.execution.TaskDispatcher;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallation;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The revisions the task pages poll: a short text that changes when the page would show other text, so the page
 * reloads itself then and only then. Each is free of commas, so poll.js can split its fields safely.
 */
@Component
@RequiredArgsConstructor
public class TaskRevisions {

    private final TaskAttemptRepository attempts;
    private final PipelineExecutionRepository executions;
    private final TaskDispatcher dispatcher;
    private final TaskQuery tasks;

    /** My tasks: the scan, the attempts, the queue, and the numbers of every run that works now. */
    public String myTasks(TaskInstallation installation) {
        String problem = installation.getSyncProblem() == null ? "none" : "present";
        return installation.isSyncComplete() + "|" + problem + "|" + installation.getUpdatedAt()
                + "|" + attempts.findLatestUpdate().map(Instant::toString).orElse("none")
                + "|" + attempts.count() + "|" + (dispatcher.queueProblem() == null ? "moving" : "stuck")
                + "|" + running();
    }

    /**
     * The task page: everything its status card and attempt rows show but the token counts, which
     * {@link TaskSpending.Tokens} leaves out.
     */
    public static String task(TaskDetail task, List<AttemptRow> attemptRows) {
        return hash(task + "|" + attemptRows);
    }

    /**
     * What the strip and the Runs rows show for each task whose run works now, a first implementation or a PR
     * feedback round. Usage events change no attempt row, so without this the cost and the minutes, also the time
     * the agent worked while a PR feedback round runs, would stand still until the run ends.
     */
    private String running() {
        var shown = executions.findByStatus(ExecutionStatus.RUNNING).stream()
                .map(execution -> attempts.findByExecutionId(execution.getId()))
                .flatMap(Optional::stream)
                .map(TaskAttempt::getTaskId)
                .distinct()
                .sorted()
                .map(tasks::detail)
                .map(TaskRevisions::shown)
                .collect(Collectors.joining(";"));
        return shown.isEmpty() ? "idle" : hash(shown);
    }

    private static String shown(TaskView view) {
        var row = RunRows.row(view, FeedbackStanding.NONE);
        var strip = RunRows.strip(List.of(view)).map(InProgressStrip::run).orElse("");
        return row.cost() + "|" + row.when() + "|" + row.facts() + "|" + strip;
    }

    private static String hash(String shown) {
        return Integer.toHexString(shown.hashCode());
    }
}
