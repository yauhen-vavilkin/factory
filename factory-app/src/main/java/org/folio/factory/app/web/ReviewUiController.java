package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.devfactory.DevFactoryConfiguration;
import org.folio.factory.devfactory.feedback.FeedbackWorker;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server-rendered HITL console: a review inbox and a review detail page with
 * inline artifact editing. Plain HTML forms post against the same decision
 * service the REST API uses.
 */
@Controller
@RequiredArgsConstructor
public class ReviewUiController {

    private static final String ARTIFACT_FIELD_PREFIX = "artifact:";
    private static final int PAGE_SIZE = 50;
    private static final List<String> STATUS_TABS =
            List.of("PENDING", "APPROVED", "AMENDED", "REJECTED", "ALL");

    private final HitlReviewRepository reviews;
    private final HitlDecisionService decisionService;
    private final JsonMapper jsonMapper;
    private final PipelineExecutionRepository executions;

    /**
     * One inbox row. A Developer Flow run that waits for PR comments is named by its task key and opens its run
     * page, where its PR feedback is worked; every other review opens the review page.
     */
    public record ReviewRow(UUID id, String title, String flowId, String gateId, PageTime createdAt,
                            HitlReviewStatus status, String decision, String reviewer, PageTime decidedAt, String url,
                            String action) {
    }

    @GetMapping("/reviews")
    public String reviews(@RequestParam(name = "status", defaultValue = "PENDING") String status,
                          @RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        String selected = normaliseStatus(status);
        Page<HitlReview> paged = null;
        List<HitlReview> rows;
        if ("PENDING".equals(selected)) {
            // The inbox stays unpaged by design: it is bounded and worked top-down.
            rows = reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING);
        } else {
            Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                    Sort.by(Sort.Direction.DESC, "createdAt"));
            paged = "ALL".equals(selected)
                    ? reviews.findAll(pageable)
                    : reviews.findByStatus(HitlReviewStatus.valueOf(selected), pageable);
            rows = paged.getContent();
        }
        var taskKeys = prFeedbackTaskKeys(rows);
        model.addAttribute("reviews", rows.stream().map(review -> reviewRow(review, taskKeys)).toList());
        model.addAttribute("statusTabs", STATUS_TABS);
        model.addAttribute("selectedStatus", selected);
        model.addAttribute("showDecision", !"PENDING".equals(selected));
        model.addAttribute("page", paged);
        model.addAttribute("baseUrl", "/reviews?status=" + selected);
        return "reviews";
    }

    private static String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PENDING";
        }
        String upper = status.strip().toUpperCase();
        return STATUS_TABS.contains(upper) ? upper : "PENDING";
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
            return UiFormat.errorRedirect("/reviews/" + id, e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            // HitlReview is @Version-ed: a concurrent reviewer's decision committed first.
            return UiFormat.errorRedirect("/reviews/" + id,
                    "This review was decided concurrently; reload to see the outcome");
        }
    }

    private ReviewRow reviewRow(HitlReview review, Map<UUID, String> taskKeys) {
        JsonNode reviewPackage = jsonMapper.readTree(review.getReviewPackage());
        var flowId = reviewPackage.path("flowId").asString();
        var taskKey = isPrFeedback(review, flowId) ? taskKeys.get(review.getExecutionId()) : null;
        var title = taskKey == null ? reviewPackage.path("title").asString() : "Waiting for PR comments: " + taskKey;
        var url = taskKey == null ? "/reviews/" + review.getId()
                : "/executions/" + review.getExecutionId() + "#pr-feedback";
        return new ReviewRow(review.getId(), title, flowId, review.getGateId(),
                PageTime.timestamp(review.getCreatedAt()), review.getStatus(), review.getDecision(),
                review.getReviewer(), PageTime.timestamp(review.getDecidedAt()), url,
                taskKey == null ? "Review" : "Open run");
    }

    /** The task key of each run that waits for PR comments, read in one query for the whole page. */
    private Map<UUID, String> prFeedbackTaskKeys(List<HitlReview> rows) {
        Set<UUID> ids = rows.stream().filter(review -> FeedbackWorker.GATE_ID.equals(review.getGateId()))
                .map(HitlReview::getExecutionId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return executions.findAllById(ids).stream()
                .filter(execution -> DevFactoryConfiguration.FLOW_ID.equals(execution.getFlowId()))
                .filter(execution -> !taskKey(execution).isBlank())
                .collect(Collectors.toMap(PipelineExecution::getId, this::taskKey));
    }

    private String taskKey(PipelineExecution execution) {
        return UiFormat.parse(execution.getTriggerPayload(), jsonMapper).path("issueKey").asString("");
    }

    private static boolean isPrFeedback(HitlReview review, String flowId) {
        return FeedbackWorker.GATE_ID.equals(review.getGateId()) && DevFactoryConfiguration.FLOW_ID.equals(flowId);
    }
}
