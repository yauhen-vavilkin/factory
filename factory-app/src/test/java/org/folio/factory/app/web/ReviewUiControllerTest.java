package org.folio.factory.app.web;

import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.HitlReviewStatus;
import org.folio.factory.core.hitl.HitlDecision;
import org.folio.factory.core.hitl.HitlDecisionService;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class ReviewUiControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REVIEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private HitlReviewRepository reviews;

    @Mock
    private HitlDecisionService decisionService;

    @Captor
    private ArgumentCaptor<Map<String, String>> amendmentsCaptor;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ReviewUiController(reviews, decisionService, json))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    @Test
    void decide_amend_forwardsOnlyArtifactPrefixedParamsWithPrefixStripped() throws Exception {
        mvc.perform(post("/reviews/{id}/decision", REVIEW_ID)
                        .param("decision", "AMEND")
                        .param("reviewer", "qa")
                        .param("artifact:test_plan.md", "new content")
                        .param("unrelated", "x"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reviews"));

        verify(decisionService).decide(eq(REVIEW_ID), eq(HitlDecision.AMEND), eq("qa"), isNull(),
                amendmentsCaptor.capture());
        assertThat(amendmentsCaptor.getValue()).containsExactlyEntriesOf(Map.of("test_plan.md", "new content"));
    }

    @Test
    void decide_approve_ignoresArtifactParams() throws Exception {
        mvc.perform(post("/reviews/{id}/decision", REVIEW_ID)
                        .param("decision", "APPROVE")
                        .param("reviewer", "qa")
                        .param("artifact:test_plan.md", "should be ignored"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reviews"));

        verify(decisionService).decide(eq(REVIEW_ID), eq(HitlDecision.APPROVE), eq("qa"), isNull(),
                amendmentsCaptor.capture());
        assertThat(amendmentsCaptor.getValue()).isEmpty();
    }

    @Test
    void decide_invalidDecision_redirectsWithEncodedErrorWithoutDelegation() throws Exception {
        mvc.perform(post("/reviews/{id}/decision", REVIEW_ID)
                        .param("decision", "MAYBE")
                        .param("reviewer", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith("/reviews/" + REVIEW_ID + "?error=")
                        .contains("MAYBE"));

        verifyNoInteractions(decisionService);
    }

    @Test
    void decide_serviceRejects_redirectsWithEncodedMessage() throws Exception {
        when(decisionService.decide(REVIEW_ID, HitlDecision.APPROVE, "qa", null, Map.of()))
                .thenThrow(new IllegalArgumentException("boom message"));

        mvc.perform(post("/reviews/{id}/decision", REVIEW_ID)
                        .param("decision", "APPROVE")
                        .param("reviewer", "qa"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reviews/" + REVIEW_ID + "?error=boom+message"));
    }

    // ----- inbox status filtering -----

    @Test
    void reviews_default_showsPendingListWithoutDecisionColumns() throws Exception {
        when(reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING)).thenReturn(List.of());

        mvc.perform(get("/reviews"))
                .andExpect(status().isOk())
                .andExpect(view().name("reviews"))
                .andExpect(model().attribute("selectedStatus", "PENDING"))
                .andExpect(model().attribute("showDecision", false))
                .andExpect(model().attribute("page", (Object) null));

        verify(reviews).findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING);
    }

    @Test
    void reviews_decidedStatus_pagesByStatusAndShowsDecisionColumns() throws Exception {
        when(reviews.findByStatus(eq(HitlReviewStatus.APPROVED), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mvc.perform(get("/reviews").param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", "APPROVED"))
                .andExpect(model().attribute("showDecision", true))
                .andExpect(model().attributeExists("page"));

        verify(reviews).findByStatus(eq(HitlReviewStatus.APPROVED), any());
    }

    @Test
    void reviews_all_usesFindAllOrdered() throws Exception {
        when(reviews.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        mvc.perform(get("/reviews").param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", "ALL"))
                .andExpect(model().attribute("showDecision", true))
                .andExpect(model().attribute("page", (Object) null));

        verify(reviews).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void reviews_invalidStatus_fallsBackToPending() throws Exception {
        when(reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING)).thenReturn(List.of());

        mvc.perform(get("/reviews").param("status", "NONSENSE"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", "PENDING"));

        verify(reviews).findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING);
        verifyNoInteractions(decisionService);
    }

    @Test
    void reviews_pendingRow_formatsCreatedAtAndLeavesDecidedAtNull() throws Exception {
        HitlReview review = new HitlReview(EXECUTION_ID, "qa-gate-1", 2, "{\"title\":\"t\",\"flowId\":\"f\"}");
        when(reviews.findByStatusOrderByCreatedAtAsc(HitlReviewStatus.PENDING)).thenReturn(List.of(review));

        var result = mvc.perform(get("/reviews")).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("reviews");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("createdAt").toString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(rows.get(0).get("decidedAt")).isNull();
    }

    @Test
    void reviews_decidedRow_formatsDecidedAtAsUtcPattern() throws Exception {
        HitlReview review = new HitlReview(EXECUTION_ID, "qa-gate-1", 2, "{\"title\":\"t\",\"flowId\":\"f\"}");
        review.decide(HitlReviewStatus.APPROVED, "APPROVE", "qa", "ok", null);
        when(reviews.findByStatus(eq(HitlReviewStatus.APPROVED), any()))
                .thenReturn(new PageImpl<>(List.of(review), PageRequest.of(0, 50), 1));

        var result = mvc.perform(get("/reviews").param("status", "APPROVED"))
                .andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("reviews");
        assertThat(rows.get(0).get("decidedAt").toString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void review_get_rendersReviewViewWithModel() throws Exception {
        HitlReview review = new HitlReview(EXECUTION_ID, "qa-gate-1", 2, "{\"title\":\"Review test plan\"}");
        when(reviews.findById(review.getId())).thenReturn(Optional.of(review));

        mvc.perform(get("/reviews/{id}", review.getId()).param("error", "oops"))
                .andExpect(status().isOk())
                .andExpect(view().name("review"))
                .andExpect(model().attribute("review", review))
                .andExpect(model().attributeExists("pkg"))
                .andExpect(model().attribute("error", "oops"));
    }
}
