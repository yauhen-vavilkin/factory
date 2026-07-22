package org.folio.factory.app.web;

import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private PipelineRouter router;

    @Captor
    private ArgumentCaptor<TriggerEvent> eventCaptor;

    private MockMvc mvc(String sharedSecret) {
        return MockMvcBuilders.standaloneSetup(new WebhookController(router, sharedSecret))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void jira_wrongToken_forbiddenWithoutRouting() throws Exception {
        mvc("s3cret").perform(post("/api/webhooks/jira").param("token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid webhook token"));

        verifyNoInteractions(router);
    }

    @Test
    void jira_malformedJsonBody_badRequestJsonError() throws Exception {
        mvc("").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));

        verifyNoInteractions(router);
    }

    @Test
    void jira_missingToken_forbiddenWithoutRouting() throws Exception {
        mvc("s3cret").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(router);
    }

    @Test
    void jira_blankSecret_noToken_routed() throws Exception {
        when(router.route(any())).thenReturn(List.of(EXECUTION_ID));

        mvc("").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookEvent\":\"jira:issue_updated\",\"issue\":{\"key\":\"ERM-7\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionIds[0]").value(EXECUTION_ID.toString()));
    }

    @Test
    void jira_issueUpdated_normalisedToTransitionedEventWithIssueKey() throws Exception {
        when(router.route(any())).thenReturn(List.of());

        mvc("").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"webhookEvent\":\"jira:issue_updated\",\"issue\":{\"key\":\"ERM-7\"}}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(router).route(eventCaptor.capture());
        TriggerEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("jira.issue.transitioned");
        assertThat(event.source()).isEqualTo("jira-webhook");
        assertThat(event.payload().path("issueKey").asString()).isEqualTo("ERM-7");
    }

    @Test
    void jira_existingIssueKey_notOverwrittenByNormalisation() throws Exception {
        when(router.route(any())).thenReturn(List.of());

        mvc("").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueKey\":\"KEEP-1\",\"issue\":{\"key\":\"OTHER-2\"}}"))
                .andExpect(status().isNoContent());

        verify(router).route(eventCaptor.capture());
        assertThat(eventCaptor.getValue().payload().path("issueKey").asString()).isEqualTo("KEEP-1");
    }

    @Test
    void jira_missingWebhookEvent_mappedToGenericJiraEvent() throws Exception {
        when(router.route(any())).thenReturn(List.of());

        mvc("").perform(post("/api/webhooks/jira")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issue\":{\"key\":\"ERM-7\"}}"))
                .andExpect(status().isNoContent());

        verify(router).route(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo("jira.event");
    }

    @Test
    void github_pushEventHeader_routedAsGithubPush() throws Exception {
        when(router.route(any())).thenReturn(List.of(EXECUTION_ID));

        mvc("").perform(post("/api/webhooks/github")
                        .header("X-GitHub-Event", "push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\":\"refs/heads/main\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionIds[0]").value(EXECUTION_ID.toString()));

        verify(router).route(eventCaptor.capture());
        TriggerEvent event = eventCaptor.getValue();
        assertThat(event.type()).isEqualTo("github.push");
        assertThat(event.source()).isEqualTo("github-webhook");
    }
}
