package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.ArtifactRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Server-rendered artifact browser: a cross-execution, newest-first feed of the
 * immutable artifact store, and a detail view per artifact version. Rows carry
 * the flow and the run of the owning execution, as the Executions list names them, batch-loaded per page.
 */
@Controller
@RequiredArgsConstructor
public class ArtifactUiController {

    private static final int DEFAULT_SIZE = 50;

    private final ArtifactRepository artifacts;
    private final PipelineExecutionRepository executions;
    private final FlowRegistry flowRegistry;
    private final TaskAttemptRepository taskAttempts;
    private final JsonMapper jsonMapper;

    /** The flow name and the run name of the run that wrote an artifact. */
    private record RunLabel(String flowName, String runName) {
    }

    @GetMapping("/artifacts")
    public String artifacts(@RequestParam(name = "name", required = false) String name,
                            @RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "50") int size,
                            Model model) {
        int clampedSize = PageValidation.clampSize(size, DEFAULT_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampedSize);
        boolean hasName = name != null && !name.isBlank();

        Slice<Artifact> result = hasName
                ? artifacts.findByNameOrderByCreatedAtDescIdDesc(name, pageable)
                : artifacts.findAllByOrderByCreatedAtDescIdDesc(pageable);

        var runs = runLabels(result.getContent());
        model.addAttribute("artifacts", result.getContent().stream()
                .map(artifact -> summaryRow(artifact, runs, PageTime.day(artifact.getCreatedAt())))
                .toList());
        model.addAttribute("page", result);
        model.addAttribute("names", declaredArtifactNames());
        model.addAttribute("selectedName", hasName ? name : "");
        model.addAttribute("filtersActive", hasName);
        model.addAttribute("baseUrl", "/artifacts?name=" + UiFormat.encode(hasName ? name : "")
                + "&size=" + clampedSize);
        return "artifacts";
    }

    @GetMapping("/artifacts/{id}")
    public String artifact(@PathVariable("id") UUID id, Model model) {
        Artifact artifact = artifacts.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No artifact " + id));
        Map<String, Object> row = summaryRow(artifact, runLabels(List.of(artifact)),
                PageTime.timestamp(artifact.getCreatedAt()));
        row.put("contentType", artifact.getContentType());
        row.put("sha256", artifact.getSha256());
        row.put("artifactContent", artifact.getContent());
        model.addAttribute("artifact", row);
        return "artifact";
    }

    /** The flow and run names of the runs on the page, read with one query for the runs and one for the attempts. */
    private Map<UUID, RunLabel> runLabels(List<Artifact> pageContent) {
        List<UUID> ids = pageContent.stream().map(Artifact::getExecutionId).distinct().toList();
        var runs = executions.findAllById(ids);
        var attempts = developerAttempts(runs);
        return runs.stream().collect(Collectors.toMap(PipelineExecution::getId,
                run -> new RunLabel(UiFormat.flowName(flowRegistry, run.getFlowId()),
                        runName(run, attempts.get(run.getId()))), (one, other) -> one));
    }

    private Map<UUID, TaskAttempt> developerAttempts(List<PipelineExecution> runs) {
        var developerRuns = runs.stream().filter(ArtifactUiController::isDeveloperRun)
                .map(PipelineExecution::getId).toList();
        if (developerRuns.isEmpty()) {
            return Map.of();
        }
        return taskAttempts.findByExecutionIdIn(developerRuns).stream()
                .collect(Collectors.toMap(TaskAttempt::getExecutionId, Function.identity(), (one, other) -> one));
    }

    /** "MGRAPPS-119 · run 3" for a Developer Flow run, as the Executions list says it; the short id otherwise. */
    private String runName(PipelineExecution run, TaskAttempt attempt) {
        var idShort = UiFormat.abbreviate(run.getId().toString());
        if (!isDeveloperRun(run)) {
            return idShort;
        }
        var taskKey = UiFormat.parse(run.getTriggerPayload(), jsonMapper).path("issueKey").asString("");
        return ExecutionListPage.runName(taskKey, attempt, idShort);
    }

    private static boolean isDeveloperRun(PipelineExecution run) {
        return DevFactoryConfiguration.FLOW_ID.equals(run.getFlowId());
    }

    /**
     * {@code createdAt} is the short time in the list and the time with seconds on the artifact page; a run that
     * is gone has no flow name and keeps its short id.
     */
    private Map<String, Object> summaryRow(Artifact artifact, Map<UUID, RunLabel> runs, PageTime createdAt) {
        var run = runs.get(artifact.getExecutionId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", artifact.getId());
        row.put("name", artifact.getName());
        row.put("version", artifact.getVersion());
        row.put("executionId", artifact.getExecutionId());
        row.put("runName", run == null ? UiFormat.abbreviate(artifact.getExecutionId().toString()) : run.runName());
        row.put("flowName", run == null ? null : run.flowName());
        row.put("createdBy", artifact.getCreatedBy());
        row.put("createdAt", createdAt);
        return row;
    }

    /**
     * Every artifact name originates from a flow descriptor — step outputs plus
     * sub-flow output mappings — so the filter options come from the in-memory
     * registry instead of a DISTINCT scan over the unbounded artifact table.
     */
    private List<String> declaredArtifactNames() {
        TreeSet<String> names = new TreeSet<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            for (StepDescriptor step : flow.agentChain()) {
                names.addAll(step.outputs());
                if (step.subFlow() != null) {
                    names.addAll(step.subFlow().outputMapping().values());
                }
            }
        }
        return List.copyOf(names);
    }
}
