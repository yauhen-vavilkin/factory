package org.folio.factory.app.web;

import org.folio.factory.app.tasks.admission.TaskAdmissionService;
import org.folio.factory.app.tasks.source.TaskDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskListControllerTest {

    private static final String FLOW_PAGE = "/flows/dev-factory";
    private static final UUID TASK_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private TaskDiscoveryService discovery;

    @Mock
    private TaskAdmissionService admissions;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TaskListController(discovery, admissions)).build();
    }

    @Test
    void scan_reportsTheAssignedCount() throws Exception {
        when(discovery.sync()).thenReturn(new TaskDiscoveryService.SyncResult(true, 3, Instant.now(), null));

        mvc.perform(post(FLOW_PAGE + "/scan"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Scan complete: 3 tasks are assigned to you."));
    }

    @Test
    void scan_failureRedirectsWithTheProblem() throws Exception {
        when(discovery.sync()).thenReturn(new TaskDiscoveryService.SyncResult(false, 0, null, "Jira timed out"));

        mvc.perform(post(FLOW_PAGE + "/scan"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE + "?error=Scan+failed%3A+Jira+timed+out"));
    }

    @Test
    void addByKey_putsTheTaskInAvailable() throws Exception {
        when(admissions.addByKey("https://jira.example.org/browse/MODBAR-930"))
                .thenReturn(new TaskAdmissionService.KeyResult("MODBAR-930", TASK_ID, null));

        mvc.perform(post(FLOW_PAGE + "/add-by-key").param("key", "https://jira.example.org/browse/MODBAR-930"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "MODBAR-930 is in Available now."));
    }

    @Test
    void addByKey_refusalRedirectsWithTheReason() throws Exception {
        when(admissions.addByKey("MODBAR-930")).thenReturn(new TaskAdmissionService.KeyResult("MODBAR-930",
                TASK_ID, "MODBAR-930 has already run. Retry it from its row under Triggered."));

        mvc.perform(post(FLOW_PAGE + "/add-by-key").param("key", "MODBAR-930"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE
                        + "?error=MODBAR-930+has+already+run.+Retry+it+from+its+row+under+Triggered."));
    }

    @Test
    void addByKey_aLongJiraErrorStaysShortInTheRedirect() throws Exception {
        when(admissions.addByKey("MODX-1")).thenReturn(new TaskAdmissionService.KeyResult(
                "MODX-1", null, "Jira could not be read: " + "<html>".repeat(2000)));

        MvcResult result = mvc.perform(post(FLOW_PAGE + "/add-by-key").param("key", "MODX-1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String error = URLDecoder.decode(result.getResponse().getRedirectedUrl()
                .substring((FLOW_PAGE + "?error=").length()), StandardCharsets.UTF_8);
        assertThat(error).hasSize(UiFormat.MAX_REDIRECT_MESSAGE).startsWith("Jira could not be read: ").endsWith("…");
    }

    @Test
    void removeFromList_takesTheTaskOutOfAvailable() throws Exception {
        mvc.perform(post("/tasks/{taskId}/remove-from-list", TASK_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE))
                .andExpect(flash().attribute("message", "Removed from your list."));

        verify(discovery).removeFromList(TASK_ID);
    }

    @Test
    void removeFromList_refusalRedirectsWithTheReason() throws Exception {
        when(discovery.removeFromList(TASK_ID)).thenReturn("MODBAR-930 has already run, so it stays under Triggered.");

        mvc.perform(post("/tasks/{taskId}/remove-from-list", TASK_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(FLOW_PAGE
                        + "?error=MODBAR-930+has+already+run%2C+so+it+stays+under+Triggered."));
    }

    @Test
    void crossSitePostsAreRefusedBeforeAnyService() throws Exception {
        mvc.perform(post(FLOW_PAGE + "/scan").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(post(FLOW_PAGE + "/add-by-key").param("key", "MODBAR-1")
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tasks/{taskId}/remove-from-list", TASK_ID).header("Sec-Fetch-Site", "same-site"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(discovery, admissions);
    }

    @Test
    void sameOriginPostsPass() throws Exception {
        when(discovery.sync()).thenReturn(new TaskDiscoveryService.SyncResult(true, 1, Instant.now(), null));

        mvc.perform(post(FLOW_PAGE + "/scan").header("Origin", "http://localhost"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Scan complete: 1 task is assigned to you."));
        mvc.perform(post(FLOW_PAGE + "/scan").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().is3xxRedirection());
    }
}
