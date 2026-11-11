package org.folio.factory.app.web;

import org.folio.factory.app.EngineRecords;
import org.folio.factory.app.tasks.persistence.RunState;
import org.folio.factory.app.tasks.persistence.RunStateRepository;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Executions list. Its Status filter and every chip on it come from one {@link RunState} per run, the one the
 * Dashboard counts, so a filter shows exactly the rows whose chip says it.
 */
@Component
class ExecutionListPage {

    private static final int DEFAULT_SIZE = 20;

    private final EngineRecords engine;
    private final RunStateRepository runStates;
    private final TaskAttemptRepository taskAttempts;
    private final DeveloperExecutionView developerView;

    ExecutionListPage(EngineRecords engine, RunStateRepository runStates, TaskAttemptRepository taskAttempts,
                      JsonMapper jsonMapper) {
        this.engine = engine;
        this.runStates = runStates;
        this.taskAttempts = taskAttempts;
        this.developerView = new DeveloperExecutionView(jsonMapper);
    }

    /** The filter of the list; {@code state} is null for all runs, {@code flow} blank for every flow. */
    record Filter(RunState state, String flow, int page, int size) {
    }

    /** A Flow filter choice: the flow's name, with its id as the value. */
    record FlowOption(String id, String name) {
    }

    /**
     * One row of the list. A Developer Flow run is named by its task and attempt and has no {@code step}; another
     * run is named by its short id and shows its step number.
     */
    record Row(UUID id, String name, String flowName, String statusLabel, String statusClass, String step,
               PageTime createdAt, PageTime updatedAt, UUID parentExecutionId, String parentShort) {
    }

    String render(Filter filter, Model model) {
        var size = PageValidation.clampSize(filter.size(), DEFAULT_SIZE);
        var result = runStates.search(filter.state() == null ? "" : filter.state().name(), filter.flow(),
                PageRequest.of(Math.max(filter.page(), 0), size));
        var flowNames = engine.flows().all().stream()
                .collect(Collectors.toMap(FlowDescriptor::id, FlowDescriptor::name, (one, other) -> one,
                        LinkedHashMap::new));
        var rows = rows(result, flowNames);
        model.addAttribute("executions", rows.getContent());
        model.addAttribute("showStep", rows.stream().anyMatch(row -> row.step() != null));
        model.addAttribute("page", rows);
        model.addAttribute("states", RunState.values());
        model.addAttribute("flows", flowNames.entrySet().stream()
                .map(entry -> new FlowOption(entry.getKey(), entry.getValue())).toList());
        addFilter(filter, size, model);
        return "executions";
    }

    private static void addFilter(Filter filter, int size, Model model) {
        var selectedState = filter.state() == null ? "" : filter.state().name();
        model.addAttribute("selectedStatus", selectedState);
        model.addAttribute("selectedFlow", filter.flow());
        model.addAttribute("filtersActive", filter.state() != null || !filter.flow().isEmpty());
        model.addAttribute("baseUrl", "/executions?status=" + UiFormat.encode(selectedState)
                + "&flow=" + UiFormat.encode(filter.flow()) + "&size=" + size);
    }

    /** The rows of the page, with the runs and their attempts read in one query each. */
    private Page<Row> rows(Page<RunStateRepository.Row> page, Map<String, String> flowNames) {
        var ids = page.getContent().stream().map(RunStateRepository.Row::getId).toList();
        Map<UUID, PipelineExecution> runs = byId(engine.executions().findAllById(ids), PipelineExecution::getId);
        var developerRuns = runs.values().stream()
                .filter(run -> DevFactoryConfiguration.FLOW_ID.equals(run.getFlowId()))
                .map(PipelineExecution::getId).toList();
        Map<UUID, TaskAttempt> attempts = developerRuns.isEmpty() ? Map.of()
                : byId(taskAttempts.findByExecutionIdIn(developerRuns), TaskAttempt::getExecutionId);
        return page.map(row -> row(runs.get(row.getId()), RunState.valueOf(row.getState()),
                attempts.get(row.getId()), flowNames));
    }

    private Row row(PipelineExecution execution, RunState state, TaskAttempt attempt, Map<String, String> flowNames) {
        var idShort = UiFormat.abbreviate(execution.getId().toString());
        var parentId = execution.getParentExecutionId();
        var developerRun = DevFactoryConfiguration.FLOW_ID.equals(execution.getFlowId());
        var facts = developerRun ? developerView.listFacts(execution,
                name -> engine.artifacts().getLatest(execution.getId(), name)) : null;
        var chip = chip(state, execution, pullRequest(attempt, facts));
        return new Row(execution.getId(), developerRun ? runName(facts.taskKey(), attempt, idShort) : idShort,
                flowNames.getOrDefault(execution.getFlowId(), execution.getFlowId()), chip.label(), chip.cssClass(),
                developerRun ? null : String.valueOf(execution.getCurrentStepIndex()),
                PageTime.day(execution.getCreatedAt()), PageTime.day(execution.getUpdatedAt()), parentId,
                parentId == null ? null : UiFormat.abbreviate(parentId.toString()));
    }

    /** "MGRENTITLE-229 · attempt 2", so the runs of one task differ; the key alone for a run no attempt started. */
    static String runName(String taskKey, TaskAttempt attempt, String idShort) {
        if (taskKey.isBlank()) {
            return idShort;
        }
        return attempt == null ? taskKey : taskKey + " · attempt " + attempt.getOrdinal();
    }

    /** The pull request of the attempt, or the one the artifacts of a run without an attempt name. */
    private static RunFacts.PullRequest pullRequest(TaskAttempt attempt, DeveloperExecutionView.ListFacts facts) {
        if (attempt != null) {
            var url = attempt.getPublicationIdentity();
            return new RunFacts.PullRequest(url == null ? "" : url, attempt.getDraftReasons() != null);
        }
        return facts == null ? new RunFacts.PullRequest("", false) : facts.pullRequest();
    }

    /** The chip says the state of the run; a run that opened a pull request names it, as the task page does. */
    static RunStatusCard.Chip chip(RunState state, PipelineExecution execution, RunFacts.PullRequest pullRequest) {
        return switch (state) {
            case IN_PROGRESS -> new RunStatusCard.Chip(state.getLabel(), "chip chip-info", null);
            case WAITING_FOR_REVIEW, ASKED, NEEDS_INSPECTION ->
                    new RunStatusCard.Chip(state.getLabel(), "chip chip-warning", null);
            case PULL_REQUEST -> RunStatusCard.chip("DELIVERED", pullRequest);
            case COMPLETED -> new RunStatusCard.Chip(state.getLabel(), "chip chip-success", null);
            case FAILED -> new RunStatusCard.Chip(state.getLabel(), "chip chip-danger", null);
            case CANCELLED_OR_REJECTED -> new RunStatusCard.Chip(
                    execution.getStatus() == ExecutionStatus.REJECTED ? "Rejected" : "Cancelled",
                    "chip chip-neutral", null);
        };
    }

    private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (one, other) -> one));
    }
}
