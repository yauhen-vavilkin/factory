package org.folio.factory.app.web;

import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.agents.prompt.PromptCatalog;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.agent.AgentWorkerRegistry;
import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.limits.DailyBudgetExceededException;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.registry.model.TriggerContract;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.trigger.PipelineRouter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Server-rendered flow catalog and worker library. The flow list/detail views and
 * the manual trigger dialog draw on the same registry beans as the REST
 * {@link FlowController}/{@link WorkerController}, but assemble template-friendly row
 * Maps here so the templates stay dumb. The trigger form posts against the same
 * {@link PipelineRouter} the REST trigger API uses.
 */
@Controller
public class FlowUiController {

    private static final String SAMPLES_PATTERN = "classpath:samples/*.json";

    private final FlowRegistry flowRegistry;
    private final FlowRegistryEntryRepository mirror;
    private final PipelineRouter router;
    private final AgentWorkerRegistry workers;
    private final PromptCatalog promptCatalog;
    private final JsonMapper jsonMapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public FlowUiController(FlowRegistry flowRegistry, FlowRegistryEntryRepository mirror, PipelineRouter router,
                            AgentWorkerRegistry workers, PromptCatalog promptCatalog, JsonMapper jsonMapper) {
        this.flowRegistry = flowRegistry;
        this.mirror = mirror;
        this.router = router;
        this.workers = workers;
        this.promptCatalog = promptCatalog;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/flows")
    public String flows(Model model) {
        model.addAttribute("flows", flowRegistry.all().stream().map(this::flowSummaryRow).toList());
        return "flows";
    }

    @GetMapping("/flows/{id}")
    public String flow(@PathVariable("id") String id,
                       @RequestParam(name = "error", required = false) String error, Model model) {
        FlowDescriptor descriptor = flowRegistry.find(id)
                .orElseThrow(() -> new NoSuchElementException("No registered flow with id '" + id + "'"));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", descriptor.id());
        header.put("name", descriptor.name());
        header.put("version", descriptor.version());

        List<Map<String, Object>> samples = samplesFor(descriptor.id());
        model.addAttribute("flow", header);
        model.addAttribute("steps", stepRows(descriptor));
        model.addAttribute("triggers", triggerRows(descriptor));
        model.addAttribute("retryPolicy", retryPolicyRow(descriptor));
        model.addAttribute("rawYaml", rawYaml(descriptor));
        model.addAttribute("samples", samples);
        model.addAttribute("defaultPayload", samples.isEmpty() ? "{}" : samples.get(0).get("prettyJson"));
        model.addAttribute("error", error);
        return "flow";
    }

    @PostMapping("/flows/{id}/trigger")
    public String trigger(@PathVariable("id") String id,
                          @RequestParam(name = "payload", required = false) String payload,
                          @RequestParam(name = "dedupKey", required = false) String dedupKey,
                          RedirectAttributes redirect) {
        JsonNode payloadNode;
        try {
            String body = payload == null || payload.isBlank() ? "{}" : payload;
            payloadNode = jsonMapper.readTree(body);
        } catch (JacksonException parseFailure) {
            return redirectWithError(id, "Invalid JSON payload: " + parseFailure.getMessage());
        }
        try {
            UUID executionId = router.routeManual(id, payloadNode, TriggerController.normalisedDedupKey(dedupKey));
            redirect.addFlashAttribute("message", "Execution started");
            return "redirect:/executions/" + executionId;
        } catch (FlowValidationException | IllegalArgumentException | DailyBudgetExceededException e) {
            return redirectWithError(id, e.getMessage());
        }
    }

    @GetMapping("/workers")
    public String workers(Model model) {
        List<Map<String, Object>> rows = workers.all().values().stream()
                .sorted(Comparator.comparing(AgentWorker::id))
                .map(this::workerRow)
                .toList();
        model.addAttribute("workers", rows);
        return "workers";
    }

    private Map<String, Object> flowSummaryRow(FlowDescriptor descriptor) {
        int gateCount = (int) descriptor.agentChain().stream()
                .filter(step -> step.type() == StepType.HITL_GATE).count();
        List<String> eventTypes = descriptor.triggers().stream()
                .map(TriggerContract::eventType).distinct().toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", descriptor.id());
        row.put("name", descriptor.name());
        row.put("version", descriptor.version());
        row.put("stepCount", descriptor.agentChain().size());
        row.put("gateCount", gateCount);
        row.put("eventTypes", eventTypes);
        return row;
    }

    private List<Map<String, Object>> stepRows(FlowDescriptor descriptor) {
        List<StepDescriptor> chain = descriptor.agentChain();
        List<Map<String, Object>> rows = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            StepDescriptor step = chain.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("stepId", step.stepId());
            row.put("type", step.type().name());
            row.put("label", stepLabel(step));
            row.put("sublabel", step.stepId() + " · " + step.type());
            row.put("state", "pending");
            switch (step.type()) {
                case AGENT -> {
                    row.put("workerId", step.workerId());
                    row.put("inputs", step.inputs());
                    row.put("outputs", step.outputs());
                    row.put("config", step.config());
                }
                case HITL_GATE -> row.put("gate", gateRow(step.gate()));
                case SUB_FLOW -> row.put("subFlow", subFlowRow(step.subFlow()));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String stepLabel(StepDescriptor step) {
        return switch (step.type()) {
            case AGENT -> step.workerId();
            case HITL_GATE -> step.gate().title();
            case SUB_FLOW -> "Sub-flow: " + step.subFlow().flowId();
        };
    }

    private static Map<String, Object> gateRow(HitlGateSpec gate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("gateId", gate.gateId());
        row.put("title", gate.title());
        row.put("reviewInstructions", gate.reviewInstructions());
        row.put("reviewedArtifacts", gate.reviewedArtifacts());
        return row;
    }

    private static Map<String, Object> subFlowRow(SubFlowSpec subFlow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("flowId", subFlow.flowId());
        row.put("inputMapping", subFlow.inputMapping());
        row.put("outputMapping", subFlow.outputMapping());
        return row;
    }

    private List<Map<String, Object>> triggerRows(FlowDescriptor descriptor) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TriggerContract trigger : descriptor.triggers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventType", trigger.eventType());
            row.put("filters", trigger.filters());
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> retryPolicyRow(FlowDescriptor descriptor) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("maxAttempts", descriptor.retryPolicy().maxAttempts());
        row.put("backoffSeconds", descriptor.retryPolicy().backoffSeconds());
        return row;
    }

    private String rawYaml(FlowDescriptor descriptor) {
        return mirror.findById(new FlowRegistryEntry.Key(descriptor.id(), descriptor.version()))
                .map(FlowRegistryEntry::getRawYaml)
                .orElse(null);
    }

    private List<Map<String, Object>> samplesFor(String flowId) {
        List<Map<String, Object>> samples = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(SAMPLES_PATTERN);
        } catch (IOException e) {
            return samples;
        }
        for (Resource resource : resources) {
            JsonNode root;
            try {
                root = jsonMapper.readTree(resource.getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException | JacksonException unreadable) {
                continue;
            }
            if (!flowId.equals(root.path("flowId").asString(""))) {
                continue;
            }
            JsonNode payload = root.path("payload");
            JsonNode content = payload.isMissingNode() ? jsonMapper.createObjectNode() : payload;
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("name", sampleName(resource.getFilename()));
            sample.put("prettyJson", escapeScriptClosers(
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content)));
            samples.add(sample);
        }
        samples.sort(Comparator.comparing(sample -> String.valueOf(sample.get("name"))));
        return samples;
    }

    /**
     * The prettyJson is rendered unescaped into a {@code <script type="application/json">}
     * block, so a {@code </script>} inside a sample would close the element early.
     * Escaping {@code </} as the JSON-legal {@code <\/} closes the hole and is
     * harmless in the textarea copy path (it parses back to the same value).
     */
    static String escapeScriptClosers(String prettyJson) {
        return prettyJson.replace("</", "<\\/");
    }

    private static String sampleName(String filename) {
        if (filename == null) {
            return "sample";
        }
        return filename.endsWith(".json") ? filename.substring(0, filename.length() - ".json".length()) : filename;
    }

    private Map<String, Object> workerRow(AgentWorker worker) {
        boolean llm = worker instanceof AbstractLlmAgentWorker;
        List<Map<String, Object>> usedBy = new ArrayList<>();
        for (FlowDescriptor flow : flowRegistry.all()) {
            for (StepDescriptor step : flow.agentChain()) {
                if (step.type() == StepType.AGENT && worker.id().equals(step.workerId())) {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("flowId", flow.id());
                    ref.put("stepId", step.stepId());
                    usedBy.add(ref);
                }
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", worker.id());
        row.put("className", worker.getClass().getSimpleName());
        row.put("llm", llm);
        row.put("prompts", promptCatalog.promptsFor(worker.id()));
        row.put("usedBy", usedBy);
        return row;
    }

    private static String redirectWithError(String flowId, String message) {
        return "redirect:/flows/" + flowId + "?error="
                + URLEncoder.encode(message == null ? "Request failed" : message, StandardCharsets.UTF_8);
    }
}
