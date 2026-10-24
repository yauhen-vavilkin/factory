package org.folio.factory.app.web;

import org.folio.factory.app.pilot.admission.PilotAdmissionService;
import org.folio.factory.app.pilot.admission.PilotCommands;
import org.folio.factory.app.pilot.admission.PilotModeService;
import org.folio.factory.app.pilot.admission.PilotRetryService;
import org.folio.factory.app.pilot.persistence.PilotInstallation;
import org.folio.factory.app.pilot.persistence.PilotInstallationRepository;
import org.folio.factory.app.pilot.presentation.PilotTaskQuery;
import org.folio.factory.app.pilot.presentation.PilotTaskUsage;
import org.folio.factory.app.pilot.presentation.PilotTaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class PilotTaskUiControllerTest {

    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private PilotTaskQuery taskQuery;

    @Mock
    private PilotInstallationRepository installations;

    @Mock
    private PilotAdmissionService admissions;

    @Mock
    private PilotModeService modes;

    @Mock
    private PilotRetryService retries;

    @Captor
    private ArgumentCaptor<PilotCommands.RunSelected> runCaptor;

    @Captor
    private ArgumentCaptor<PilotCommands.Retry> retryCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new PilotTaskUiController(taskQuery, installations, admissions, modes, retries))
                .setControllerAdvice(new UiExceptionHandler())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    // ----- GET routes are read-only -----

    @Test
    void tasks_rendersListFromQueryOnly() throws Exception {
        when(taskQuery.list(any(Pageable.class))).thenReturn(pageOf(
                taskView(TASK_ID, "MODBAR-901", PilotTaskView.TaskState.AWAITING_SELECTION, true, false)));
        when(installations.findById(1)).thenReturn(Optional.of(installation(false, null)));

        mvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(view().name("pilot-tasks"))
                .andExpect(model().attributeExists("tasks", "page", "modeValue", "authorityFingerprint"));

        verify(taskQuery).list(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        verifyNoInteractions(admissions, modes, retries);
    }

    @Test
    void tasks_clampsLenientPageSize() throws Exception {
        when(taskQuery.list(any(Pageable.class))).thenReturn(pageOf());
        when(installations.findById(1)).thenReturn(Optional.of(installation(false, null)));

        mvc.perform(get("/tasks").param("size", "5000"));

        verify(taskQuery).list(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void taskDetail_rendersProjectionOnly() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(taskView(TASK_ID, "MODBAR-911", PilotTaskView.TaskState.STOPPED,
                false, true, attempt("STOPPED")));

        var result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("pilot-task"))
                .andReturn();

        @SuppressWarnings("unchecked")
        var task = (Map<String, Object>) result.getModelAndView().getModel().get("task");
        assertThat(task)
                .containsEntry("stateLabel", "Could not complete")
                .containsEntry("retryAllowed", true)
                .containsEntry("version", 3L)
                .containsEntry("inputFingerprint", "revision-MODBAR-911")
                .containsEntry("destinationFingerprint", "dest-MODBAR-911");
        verifyNoInteractions(admissions, modes, retries);
    }

    @Test
    void taskDetail_knownPullRequestExplainsRetryUnavailable() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenReturn(
                taskView(TASK_ID, "MODBAR-912", PilotTaskView.TaskState.PR_READY, false, false));

        var result = mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(detail(result))
                .containsEntry("retryAllowed", false)
                .containsEntry("retryNote", "Retry is unavailable: the delivered pull request is under human review.");
        verifyNoInteractions(admissions, modes, retries);
    }

    @Test
    void taskDetail_disabledEligibilityExplainsMissingDestination() throws Exception {
        UUID taskId = TASK_ID;
        PilotTaskView unsupported = new PilotTaskView(taskId, "MODBAR-913",
                "https://jira.example.org/browse/MODBAR-913", "Title of MODBAR-913", "MODBAR",
                PilotTaskView.TaskState.UNSUPPORTED, "no repository mapping for project MODX", null,
                null, null, true, "INELIGIBLE", "no repository mapping for project MODX",
                2L, "revision-MODBAR-913", "dest-MODBAR-913",
                Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"),
                List.of(), emptyUsage(), new PilotTaskView.AllowedActions(false, false, false));
        when(taskQuery.detail(taskId)).thenReturn(unsupported);

        var result = mvc.perform(get("/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(detail(result))
                .containsEntry("stateLabel", "Not eligible")
                .containsEntry("retryAllowed", false)
                .containsEntry("retryNote", "Retry is unavailable: no repository mapping supports this task.");
    }

    @Test
    void taskDetail_unknownTaskRendersNotFoundPage() throws Exception {
        when(taskQuery.detail(TASK_ID)).thenThrow(new NoSuchElementException("No pilot task " + TASK_ID));

        mvc.perform(get("/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void poll_reportsRevisionWithoutCommandServices() throws Exception {
        when(taskQuery.list(any(Pageable.class))).thenReturn(pageOf(
                taskView(TASK_ID, "MODBAR-901", PilotTaskView.TaskState.AWAITING_SELECTION, true, false)));
        when(installations.findById(1)).thenReturn(Optional.of(installation(true, null)));

        mvc.perform(get("/tasks/poll"))
                .andExpect(status().isOk());

        verifyNoInteractions(admissions, modes, retries);
    }

    // ----- run selected -----

    @Test
    void runSelected_mapsFormToRunSelectedCommand() throws Exception {
        when(admissions.runSelected(any())).thenReturn(
                new PilotAdmissionService.AdmissionResult(List.of(TASK_ID), Map.of()));

        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "3")
                        .param("input-" + TASK_ID, "revision-MODBAR-901")
                        .param("destination-" + TASK_ID, "dest-MODBAR-901")
                        .param("expectedAuthorityFingerprint", "authority-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks"))
                .andExpect(flash().attribute("message", "Admitted 1 task(s) to the pilot queue."));

        verify(admissions).runSelected(runCaptor.capture());
        PilotCommands.RunSelected run = runCaptor.getValue();
        assertThat(run.requestId()).startsWith("ui-");
        assertThat(run.requestId().length()).isLessThanOrEqualTo(63);
        assertThat(run.expectedAuthorityFingerprint()).isEqualTo("authority-1");
        assertThat(run.selections()).containsExactly(new PilotCommands.Selection(
                TASK_ID, 3L, "revision-MODBAR-901", "dest-MODBAR-901"));
    }

    @Test
    void runSelected_surfacesPerRowConflictExplanations() throws Exception {
        when(admissions.runSelected(any())).thenReturn(new PilotAdmissionService.AdmissionResult(List.of(),
                Map.of(TASK_ID, "task changed since selection; review and select again")));
        when(taskQuery.detail(TASK_ID)).thenReturn(
                taskView(TASK_ID, "MODBAR-901", PilotTaskView.TaskState.AWAITING_SELECTION, true, false));

        var result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "1")
                        .param("input-" + TASK_ID, "stale-input")
                        .param("destination-" + TASK_ID, "dest")
                        .param("expectedAuthorityFingerprint", "authority-1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks?error=")
                .contains("MODBAR-901")
                .contains("task+changed+since+selection");
    }

    @Test
    void runSelected_withoutSelection_redirectsWithoutCallingAdmission() throws Exception {
        mvc.perform(post("/tasks/run-selected"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks?error=Select+at+least+one+task%2C+then+choose+Run+selected."));

        verifyNoInteractions(admissions, modes, retries);
    }

    @Test
    void runSelected_mangledForm_redirectsWithoutCallingAdmission() throws Exception {
        mvc.perform(post("/tasks/run-selected")
                        .param("taskId", TASK_ID.toString())
                        .param("version-" + TASK_ID, "not-a-number"))
                .andExpect(status().is3xxRedirection());

        verifyNoInteractions(admissions, modes, retries);
    }

    @Test
    void runSelected_mangledTaskId_redirectsWithFriendlyExplanation() throws Exception {
        var result = mvc.perform(post("/tasks/run-selected")
                        .param("taskId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeeX"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks?error=")
                .contains("The+submitted+selection+was+malformed");

        verifyNoInteractions(admissions, modes, retries);
    }

    // ----- admission mode -----

    @Test
    void mode_switchesToAutomaticWithFingerprint() throws Exception {
        when(modes.setMode(PilotModeService.AdmissionMode.AUTOMATIC, "authority-1")).thenReturn(
                new PilotModeService.ModeResult(PilotModeService.AdmissionMode.AUTOMATIC, 2,
                        "Automatic admission enabled for the current pilot source authority"));

        mvc.perform(post("/tasks/mode")
                        .param("mode", "AUTOMATIC")
                        .param("expectedAuthorityFingerprint", "authority-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks"))
                .andExpect(flash().attribute("message",
                        "Automatic admission enabled for the current pilot source authority"));

        verifyNoInteractions(admissions, retries);
    }

    @Test
    void mode_switchesToReviewBeforeRunWithoutFingerprint() throws Exception {
        when(modes.setMode(PilotModeService.AdmissionMode.REVIEW_BEFORE_RUN, null)).thenReturn(
                new PilotModeService.ModeResult(PilotModeService.AdmissionMode.REVIEW_BEFORE_RUN, 0,
                        "Automatic admission stopped; queued and running work stays delegated"));

        mvc.perform(post("/tasks/mode").param("mode", "REVIEW_BEFORE_RUN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks"))
                .andExpect(flash().attribute("message",
                        "Automatic admission stopped; queued and running work stays delegated"));

        verify(modes).setMode(PilotModeService.AdmissionMode.REVIEW_BEFORE_RUN, null);
        verifyNoInteractions(admissions, retries);
    }

    @Test
    void mode_conflictingAuthority_redirectsWithExplanation() throws Exception {
        when(modes.setMode(PilotModeService.AdmissionMode.AUTOMATIC, "stale")).thenThrow(
                new IllegalArgumentException("The pilot source authority changed; refresh discovery"
                        + " and enable automatic admission with the current fingerprint"));

        var result = mvc.perform(post("/tasks/mode")
                        .param("mode", "AUTOMATIC")
                        .param("expectedAuthorityFingerprint", "stale"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks?error=")
                .contains("authority+changed");
    }

    @Test
    void mode_unknownModeValue_redirectsWithExplanation() throws Exception {
        when(modes.setMode(isNull(), any())).thenThrow(new IllegalArgumentException("An admission mode is required"));

        mvc.perform(post("/tasks/mode").param("mode", "NONSENSE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks?error=An+admission+mode+is+required"));
    }

    // ----- retry -----

    @Test
    void retry_mapsFormToRetryCommandAndReportsLaunch() throws Exception {
        UUID attemptId = UUID.randomUUID();
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                attemptId, EXECUTION_ID, "RUNNING", "the retry attempt launched a new execution"));

        mvc.perform(post("/tasks/{taskId}/retry", TASK_ID)
                        .param("expectedVersion", "3")
                        .param("inputFingerprint", "revision-MODBAR-901")
                        .param("destinationFingerprint", "dest-MODBAR-901"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/tasks/" + TASK_ID))
                .andExpect(flash().attribute("message",
                        "Retry admitted: the retry attempt launched a new execution"));

        verify(retries).retry(retryCaptor.capture());
        PilotCommands.Retry command = retryCaptor.getValue();
        assertThat(command.taskId()).isEqualTo(TASK_ID);
        assertThat(command.expectedVersion()).isEqualTo(3L);
        assertThat(command.inputFingerprint()).isEqualTo("revision-MODBAR-901");
        assertThat(command.destinationFingerprint()).isEqualTo("dest-MODBAR-901");
        assertThat(command.requestId()).startsWith("ui-");
        verifyNoInteractions(admissions, modes);
    }

    @Test
    void retry_staleSubmission_redirectsWithExplanation() throws Exception {
        when(retries.retry(any())).thenReturn(new PilotRetryService.RetryResult(
                UUID.randomUUID(), null, "STOPPED",
                "task changed since the retry was requested; review the current result and retry again"));

        var result = mvc.perform(post("/tasks/{taskId}/retry", TASK_ID)
                        .param("expectedVersion", "1"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/tasks/" + TASK_ID + "?error=")
                .contains("task+changed+since+the+retry");
    }

    // ----- helpers -----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(org.springframework.test.web.servlet.MvcResult result) {
        return (Map<String, Object>) result.getModelAndView().getModel().get("task");
    }

    private static PilotInstallation installation(boolean syncComplete, String problem) {
        PilotInstallation installation = PilotInstallation.initial();
        installation.setSyncComplete(syncComplete);
        installation.setSyncProblem(problem);
        installation.setObservedAuthorityFingerprint("authority-1");
        return installation;
    }

    private static Page<PilotTaskView> pageOf(PilotTaskView... views) {
        return new PageImpl<>(List.of(views), PageRequest.of(0, 20), views.length);
    }

    private static PilotTaskView taskView(UUID taskId, String key, PilotTaskView.TaskState state,
                                      boolean select, boolean retry,
                                      PilotTaskView.AttemptHistoryEntry... attempts) {
        return new PilotTaskView(taskId, key, "https://jira.example.org/browse/" + key, "Title of " + key,
                "MODBAR", state,
                state == PilotTaskView.TaskState.STOPPED ? "verification: 2 tests failed" : null,
                state == PilotTaskView.TaskState.STOPPED ? "verification" : null,
                "mod-bar",
                state == PilotTaskView.TaskState.PR_READY ? "https://github.com/folio-org/mod-bar/pull/7" : null,
                true, "ELIGIBLE", "repository mod-bar", 3L, "revision-" + key, "dest-" + key,
                Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"),
                List.of(attempts), emptyUsage(),
                new PilotTaskView.AllowedActions(select, retry, false));
    }

    private static PilotTaskView taskView(UUID taskId, String key, PilotTaskView.TaskState state,
                                      boolean select, boolean retry) {
        return taskView(taskId, key, state, select, retry, new PilotTaskView.AttemptHistoryEntry[0]);
    }

    private static PilotTaskView.AttemptHistoryEntry attempt(String state) {
        return new PilotTaskView.AttemptHistoryEntry(UUID.randomUUID(), 1, "INITIAL", "MANUAL", state,
                "verification", "verification: 2 tests failed", null, EXECUTION_ID, "COMPLETED",
                Instant.parse("2026-10-23T08:00:00Z"), Instant.parse("2026-10-23T09:00:00Z"),
                true, 600, emptyUsage());
    }

    private static PilotTaskUsage emptyUsage() {
        return new PilotTaskUsage(0, Map.of(), new TreeMap<String, BigDecimal>(), true, false,
                List.of(), List.of());
    }
}
