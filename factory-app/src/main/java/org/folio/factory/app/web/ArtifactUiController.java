package org.folio.factory.app.web;

import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.repository.ArtifactRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Server-rendered artifact browser: a cross-execution, newest-first feed of the
 * immutable artifact store, and a detail view per artifact version. Rows carry
 * the owning execution's flow id, batch-loaded per page.
 */
@Controller
public class ArtifactUiController {

    private static final int DEFAULT_SIZE = 50;

    private final ArtifactRepository artifacts;
    private final PipelineExecutionRepository executions;
    private final FlowRegistry flowRegistry;

    public ArtifactUiController(ArtifactRepository artifacts, PipelineExecutionRepository executions,
                                FlowRegistry flowRegistry) {
        this.artifacts = artifacts;
        this.executions = executions;
        this.flowRegistry = flowRegistry;
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

        Map<UUID, String> flowByExecution = flowIds(result.getContent());
        model.addAttribute("artifacts", result.getContent().stream()
                .map(artifact -> summaryRow(artifact, flowByExecution)).toList());
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
        Map<String, Object> row = summaryRow(artifact, flowIds(List.of(artifact)));
        row.put("contentType", artifact.getContentType());
        row.put("sha256", artifact.getSha256());
        row.put("artifactContent", artifact.getContent());
        model.addAttribute("artifact", row);
        return "artifact";
    }

    private Map<UUID, String> flowIds(List<Artifact> pageContent) {
        List<UUID> ids = pageContent.stream().map(Artifact::getExecutionId).distinct().toList();
        Map<UUID, String> byId = new HashMap<>();
        for (PipelineExecution execution : executions.findAllById(ids)) {
            byId.put(execution.getId(), execution.getFlowId());
        }
        return byId;
    }

    private Map<String, Object> summaryRow(Artifact artifact, Map<UUID, String> flowByExecution) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", artifact.getId());
        row.put("name", artifact.getName());
        row.put("version", artifact.getVersion());
        row.put("executionId", artifact.getExecutionId());
        row.put("executionShort", UiFormat.abbreviate(artifact.getExecutionId().toString()));
        row.put("flowId", flowByExecution.get(artifact.getExecutionId()));
        row.put("createdBy", artifact.getCreatedBy());
        row.put("createdAt", UiFormat.format(artifact.getCreatedAt()));
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
