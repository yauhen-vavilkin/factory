package org.folio.factory.app.tasks;

import org.folio.factory.app.support.FactoryIntegrationTest;
import org.folio.factory.app.tasks.persistence.RunState;
import org.folio.factory.app.tasks.persistence.RunStateRepository;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.web.dashboard.DashboardStatsService;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.client.RestClient;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Status filter of the Executions list and the Dashboard counts read one state per run on the real database: a
 * Developer Flow run takes it from its attempt, any other run from its engine status, and every chip on a filtered
 * list says the filter.
 */
@FactoryIntegrationTest
class RunStateIntegrationTest {

    private static final String ASKED_REASON = "clarify-implementation: " + TaskStops.DECISION_REASON + "Which API?";
    private static final Pattern CHIP = Pattern.compile("<td><span class=\"chip [a-z-]+\">([^<]+)</span></td>");

    @LocalServerPort
    int port;

    @Autowired
    PipelineExecutionRepository executions;

    @Autowired
    TaskRepository tasks;

    @Autowired
    TaskAttemptRepository attempts;

    @Autowired
    RunStateRepository runStates;

    @Autowired
    DashboardStatsService dashboard;

    @Autowired
    ArtifactStore artifactStore;

    private final Map<UUID, RunState> expected = new HashMap<>();
    private int seeded;

    // One test: the class shares one database across its methods, so a second seed would double the counts.
    @Test
    void theFilterTheChipsAndTheDashboardSayTheSameStateOfEveryRun() {
        seed();
        assertTheFilterAndTheDashboardCountTheSameStateOfEveryRun();
        assertEveryChipOfAFilteredListSaysTheFilter();
    }

    private void seed() {
        engineRun(ExecutionStatus.FAILED_ESCALATED, RunState.FAILED);
        engineRun(ExecutionStatus.COMPLETED, RunState.COMPLETED);
        engineRun(ExecutionStatus.AWAITING_HITL, RunState.WAITING_FOR_REVIEW);
        engineRun(ExecutionStatus.RUNNING, RunState.IN_PROGRESS);
        engineRun(ExecutionStatus.REJECTED, RunState.CANCELLED_OR_REJECTED);
        // A Developer Flow run that ran to the end for the engine is the failed attempt it stands for.
        attemptRun(ExecutionStatus.COMPLETED, TaskAttempt.State.STOPPED, "verification: 2 tests failed",
                RunState.FAILED);
        attemptRun(ExecutionStatus.COMPLETED, TaskAttempt.State.STOPPED, ASKED_REASON, RunState.ASKED);
        // The words of a question count only right after the first ": ", as TaskStops.asksForAnswer reads them.
        attemptRun(ExecutionStatus.COMPLETED, TaskAttempt.State.STOPPED, "shared: " + ASKED_REASON, RunState.FAILED);
        attemptRun(ExecutionStatus.AWAITING_HITL, TaskAttempt.State.PR_READY, null, RunState.PULL_REQUEST);
        // The engine run ended, but the dispatcher has not closed the attempt yet.
        attemptRun(ExecutionStatus.COMPLETED, TaskAttempt.State.RUNNING, null, RunState.IN_PROGRESS);
        attemptRun(ExecutionStatus.COMPLETED, TaskAttempt.State.NEEDS_INSPECTION, null, RunState.NEEDS_INSPECTION);
        // A Developer Flow run with no attempt reads the same from its artifacts: no pull request is a failure.
        unboundRun(ExecutionStatus.COMPLETED, null, null, RunState.FAILED);
        unboundRun(ExecutionStatus.AWAITING_HITL, DeliveryWorker.DELIVERY, "{\"state\":\"DELIVERED\","
                + "\"pullRequestUrl\":\"https://github.com/folio-org/mod-bar/pull/9\"}", RunState.PULL_REQUEST);
        unboundRun(ExecutionStatus.COMPLETED, DevelopWorker.OUTCOME, "{\"status\":\"NEEDS_DECISION\"}",
                RunState.ASKED);
        unboundRun(ExecutionStatus.RUNNING, null, null, RunState.IN_PROGRESS);
    }

    private void unboundRun(ExecutionStatus status, String artifact, String content, RunState state) {
        var run = executions.save(withStatus(new PipelineExecution("dev-factory", "0.9.0", "{}"), status));
        if (artifact != null) {
            artifactStore.putMarkdown(run.getId(), artifact, content, "smoke");
        }
        expected.put(run.getId(), state);
    }

    private void assertTheFilterAndTheDashboardCountTheSameStateOfEveryRun() {
        for (var state : RunState.values()) {
            var found = new HashSet<UUID>();
            runStates.search(state.name(), "", PageRequest.of(0, 50))
                    .forEach(row -> found.add(row.getId()));
            assertThat(found).as(state.getLabel()).isEqualTo(idsIn(state));
        }
        Map<RunState, Long> counts = new EnumMap<>(RunState.class);
        expected.values().forEach(state -> counts.merge(state, 1L, Long::sum));
        assertThat(dashboard.countByState()).isEqualTo(counts);
        assertThat(expected.get(attemptRunWith(ASKED_REASON)))
                .isEqualTo(TaskStops.asksForAnswer(ASKED_REASON) ? RunState.ASKED : RunState.FAILED);

        var all = runStates.search("", "", PageRequest.of(0, 50));
        assertThat(all.getTotalElements()).isEqualTo(expected.size());
        all.forEach(row -> assertThat(RunState.valueOf(row.getState())).isEqualTo(expected.get(row.getId())));
        assertThat(runStates.search("", "dev-factory", PageRequest.of(0, 50)).getTotalElements()).isEqualTo(10);
    }

    private void assertEveryChipOfAFilteredListSaysTheFilter() {
        var rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        for (var state : Set.of(RunState.FAILED, RunState.COMPLETED, RunState.IN_PROGRESS)) {
            var body = rest.get().uri("/executions?status=" + state.name()).retrieve().body(String.class);
            var chips = CHIP.matcher(body).results().map(match -> match.group(1)).toList();
            assertThat(chips).as(state.getLabel()).hasSize(idsIn(state).size()).containsOnly(state.getLabel());
        }
        var failed = rest.get().uri("/executions?status=FAILED").retrieve().body(String.class);
        assertThat(failed).contains("<option value=\"FAILED\" selected=\"selected\">Failed</option>",
                "<option value=\"COMPLETED\">Completed</option>", "<th>Run</th>", "<td class=\"nowrap\">Developer Flow</td>",
                ">Developer Flow</option>");
    }

    private Set<UUID> idsIn(RunState state) {
        var ids = new HashSet<UUID>();
        expected.forEach((id, value) -> {
            if (value == state) {
                ids.add(id);
            }
        });
        return ids;
    }

    private UUID attemptRunWith(String reason) {
        return attempts.findAll().stream().filter(attempt -> reason.equals(attempt.getResultReason()))
                .findFirst().orElseThrow().getExecutionId();
    }

    private void engineRun(ExecutionStatus status, RunState state) {
        var run = executions.save(withStatus(new PipelineExecution("test-factory", "1", "{}"), status));
        expected.put(run.getId(), state);
    }

    private void attemptRun(ExecutionStatus status, TaskAttempt.State attemptState, String reason, RunState state) {
        seeded++;
        var task = tasks.save(TaskFixture.task("9500" + seeded, "MODBAR-95" + seeded));
        var run = executions.save(withStatus(new PipelineExecution("dev-factory", "0.9.0", "{}"), status));
        var attempt = TaskFixture.attempt(task.getId(), 1, attemptState, "run-95" + seeded);
        attempt.setExecutionId(run.getId());
        attempt.setResultReason(reason);
        if (attemptState == TaskAttempt.State.PR_READY) {
            attempt.setPublicationIdentity("https://github.com/folio-org/mod-bar/pull/" + seeded);
        }
        attempts.save(attempt);
        expected.put(run.getId(), state);
    }

    private static PipelineExecution withStatus(PipelineExecution execution, ExecutionStatus status) {
        execution.setStatus(status);
        return execution;
    }
}
