package org.folio.factory.app.web;

import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.jira.JiraIntakeException;
import org.folio.factory.devfactory.jira.JiraTaskService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Minimal server-rendered HITL console: a review inbox, a review detail page
 * with inline artifact editing, and an execution timeline. No JavaScript build —
 * plain HTML forms against the same decision service the REST API uses.
 */
@Controller
public class UiController {

    private static final String ARTIFACT_FIELD_PREFIX = "artifact:";

    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisionService;
    private final PipelineExecutionRepository executions;
    private final ArtifactStore artifactStore;
    private final AuditLog auditLog;
    private final JsonMapper jsonMapper;
    private final JiraTaskService jiraTasks;

    public UiController(HitlReviewRepository reviews, HitlDecisionService decisionService,
                        PipelineExecutionRepository executions, ArtifactStore artifactStore,
                        AuditLog auditLog, JsonMapper jsonMapper, JiraTaskService jiraTasks) {
        this.reviews = reviews;
        this.decisionService = decisionService;
        this.executions = executions;
        this.artifactStore = artifactStore;
        this.auditLog = auditLog;
        this.jsonMapper = jsonMapper;
        this.jiraTasks = jiraTasks;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/reviews";
    }

    @GetMapping("/reviews")
    public String reviews(Model model) {
        var pending = reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING).stream()
                .map(this::reviewRow).toList();
        model.addAttribute("reviews", pending);
        return "reviews";
    }

    @GetMapping("/reviews/{id}")
    public String review(@PathVariable("id") UUID id,
                         @RequestParam(name = "error", required = false) String error, Model model) {
        HitlReview review = reviews.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No HITL review " + id));
        JsonNode reviewPackage = jsonMapper.readTree(review.getReviewPackage());
        model.addAttribute("review", review);
        model.addAttribute("pkg", reviewPackage);
        model.addAttribute("error", error);
        return "review";
    }

    @PostMapping("/reviews/{id}/decision")
    public String decide(@PathVariable("id") UUID id,
                         @RequestParam("decision") String decision,
                         @RequestParam("reviewer") String reviewer,
                         @RequestParam(name = "comments", required = false) String comments,
                         @RequestParam Map<String, String> allParams,
                         RedirectAttributes redirect) {
        // The whole form processing sits inside one try so validation failures
        // redirect back to the review page instead of falling through to the
        // REST API's JSON exception handler. Unchanged-artifact filtering happens
        // in HitlDecisionService so REST callers get the same behavior.
        try {
            HitlDecision hitlDecision = HitlDecision.valueOf(decision);
            Map<String, String> amendments = new LinkedHashMap<>();
            if (hitlDecision == HitlDecision.AMEND) {
                for (Map.Entry<String, String> param : allParams.entrySet()) {
                    if (param.getKey().startsWith(ARTIFACT_FIELD_PREFIX)) {
                        amendments.put(param.getKey().substring(ARTIFACT_FIELD_PREFIX.length()),
                                param.getValue());
                    }
                }
            }
            decisionService.decide(id, hitlDecision, reviewer, comments, amendments);
            redirect.addFlashAttribute("message", "Decision " + hitlDecision + " recorded for review " + id);
            return "redirect:/reviews";
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException e) {
            return redirectWithError(id, e.getMessage());
        }
    }

    @GetMapping("/executions")
    public String executions(Model model) {
        model.addAttribute("executions", executions.findAllByOrderByCreatedAtDesc());
        return "executions";
    }

    @GetMapping("/executions/{id}")
    public String execution(@PathVariable("id") UUID id, Model model) {
        var execution = executions.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No execution " + id));
        model.addAttribute("execution", execution);
        model.addAttribute("artifacts", artifactStore.allForExecution(id));
        model.addAttribute("events", auditLog.forExecution(id));
        return "execution";
    }

    @GetMapping("/jira")
    public String jiraForm() {
        return "jira-run";
    }

    /** Same application service as POST /api/dev/tasks/jira and ./scripts/factory run-jira. */
    @PostMapping("/jira")
    public String runJira(@RequestParam("issueKey") String issueKey,
                          @RequestParam(name = "deliveryMode", required = false) String deliveryMode,
                          @RequestParam(name = "baseRef", required = false) String baseRef,
                          @RequestParam(name = "runKey", required = false) String runKey,
                          @RequestParam(name = "verificationPlanId", required = false) String verificationPlanId,
                          Model model) {
        model.addAttribute("issueKey", issueKey);
        model.addAttribute("deliveryMode", deliveryMode);
        model.addAttribute("baseRef", baseRef);
        model.addAttribute("runKey", runKey);
        model.addAttribute("verificationPlanId", verificationPlanId);
        try {
            JiraTaskService.RunResult result = jiraTasks.start(
                    new JiraTaskService.RunRequest(issueKey, deliveryMode, baseRef, runKey,
                            verificationPlanId));
            if (result.admitted()) {
                return "redirect:/executions/" + result.executionId();
            }
            model.addAttribute("result", result);
            model.addAttribute("errorCode", result.outcome() + (result.code() == null ? "" : " " + result.code()));
            model.addAttribute("error", result.message());
        } catch (JiraIntakeException e) {
            model.addAttribute("errorCode", e.code());
            model.addAttribute("error", e.getMessage());
        }
        return "jira-run";
    }

    private Map<String, Object> reviewRow(HitlReview review) {
        JsonNode reviewPackage = jsonMapper.readTree(review.getReviewPackage());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", review.getId());
        row.put("title", reviewPackage.path("title").asString());
        row.put("flowId", reviewPackage.path("flowId").asString());
        row.put("gateId", review.getGateId());
        row.put("createdAt", review.getCreatedAt());
        return row;
    }

    private String redirectWithError(UUID reviewId, String message) {
        return "redirect:/reviews/" + reviewId + "?error="
                + URLEncoder.encode(message == null ? "Request failed" : message, StandardCharsets.UTF_8);
    }
}
