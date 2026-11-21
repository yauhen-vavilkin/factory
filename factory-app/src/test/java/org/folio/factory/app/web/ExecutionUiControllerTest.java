package org.folio.factory.app.web;

import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.tasks.persistence.RunStateRepository;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.presentation.AttemptClock;
import org.folio.factory.app.tasks.presentation.AttemptStatus;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.tasks.presentation.RunLimits;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.domain.Artifact;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import org.folio.factory.app.EngineRecords;

@ExtendWith(MockitoExtension.class)
class ExecutionUiControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private PipelineExecutionRepository executions;

    @Mock
    private ArtifactStore artifactStore;

    @Mock
    private AuditLog auditLog;

    @Mock
    private FlowRegistry flowRegistry;

    @Mock
    private HitlReviewRepository reviews;

    @Mock
    private CodingTranscriptStore transcripts;

    @Mock
    private TaskAttemptRepository taskAttempts;

    @Mock
    private RunStateRepository runStates;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private MockMvc mvc;

    private org.folio.factory.app.tasks.presentation.TaskQuery taskQuery;

    @BeforeEach
    void setUp() {
        var estimator = DeveloperCostEstimatorTest.estimatorFor("example-small", "https://example.test/pricing",
                "0.15", "0.03", "0.50");
        taskQuery = mock(org.folio.factory.app.tasks.presentation.TaskQuery.class);
        var limits = mock(RunLimits.class);
        when(limits.limits()).thenReturn(new AttemptClock.Limits(1800, java.time.Duration.ofMinutes(60)));
        var runPage = new DeveloperRunPage(json, estimator,
                new FeedbackUi(mock(org.folio.factory.devfactory.feedback.FeedbackActions.class),
                        mock(org.folio.factory.core.service.ArtifactStore.class),
                        mock(org.folio.factory.core.repository.PipelineExecutionRepository.class)), taskQuery,
                limits, mock(TaskRows.class));
        var engine = new EngineRecords(executions, artifactStore, flowRegistry);
        mvc = MockMvcBuilders.standaloneSetup(new ExecutionUiController(engine, auditLog, reviews, json, runPage,
                        new ExecutionListPage(engine, runStates, taskAttempts, json)),
                        new TranscriptUiController(executions, taskQuery, transcripts, artifactStore))
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    // ----- list filter dispatch -----

    @Test
    void executions_noFilters_listsEveryRunNewestFirstFromTheQuery() throws Exception {
        when(runStates.search(eq(""), eq(""), any(), any())).thenReturn(emptyRows());

        mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(view().name("executions"))
                .andExpect(model().attributeExists("executions", "page", "states", "flows"));

        verify(runStates).search(eq(""), eq(""), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        // The query orders the runs itself, so the page asks for no sort of its own.
        assertThat(pageableCaptor.getValue().getSort().isUnsorted()).isTrue();
    }

    @Test
    void executions_engineRowsShowTheirStepAndStateAndNeedNoAttemptLookup() throws Exception {
        var execution = new PipelineExecution("test-factory", "1", "{}");
        execution.setCurrentStepIndex(2);
        when(runStates.search(eq(""), eq(""), any(), any()))
                .thenReturn(new PageImpl<>(List.of(new Row(execution.getId(), "FAILED")), PageRequest.of(0, 20), 1));
        when(executions.findAllById(List.of(execution.getId()))).thenReturn(List.of(execution));
        when(flowRegistry.all()).thenReturn(List.of(new FlowDescriptor("test-factory", "Test Factory", "1", null,
                null, null, null, null)));

        var result = mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("showStep", true))
                .andReturn();

        assertThat(((List<?>) result.getModelAndView().getModel().get("executions")).getFirst())
                .hasFieldOrPropertyWithValue("flowName", "Test Factory")
                .hasFieldOrPropertyWithValue("statusLabel", "Failed")
                .hasFieldOrPropertyWithValue("statusClass", "chip chip-danger")
                .hasFieldOrPropertyWithValue("step", "2");
        verifyNoInteractions(taskAttempts);
    }

    @ParameterizedTest
    @CsvSource({"failed, '', FAILED, ''", "'', test-factory, '', test-factory",
            "COMPLETED, test-factory, COMPLETED, test-factory"})
    void executions_statusAndFlowFiltersAskTheQueryForThatStateAndFlow(String status, String flow,
                                                                        String queriedStatus, String queriedFlow)
            throws Exception {
        when(runStates.search(eq(queriedStatus), eq(queriedFlow), any(), any())).thenReturn(emptyRows());

        mvc.perform(get("/executions").param("status", status).param("flow", flow))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedStatus", queriedStatus))
                .andExpect(model().attribute("selectedFlow", queriedFlow))
                .andExpect(model().attribute("filtersActive", true));

        verify(runStates).search(eq(queriedStatus), eq(queriedFlow), any(), any());
    }

    @Test
    void executions_startedFilter_countsBackFromNowAndAnyOtherValueMeansAnyTime() throws Exception {
        when(runStates.search(eq("COMPLETED"), eq(""), any(), any())).thenReturn(emptyRows());
        var since = ArgumentCaptor.forClass(java.time.OffsetDateTime.class);

        // The link of a Dashboard tile: the state and the period of the tile.
        mvc.perform(get("/executions").param("status", "COMPLETED").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedDays", "7"))
                .andExpect(model().attribute("filtersActive", true))
                .andExpect(model().attribute("baseUrl", "/executions?status=COMPLETED&flow=&days=7&size=20"));
        mvc.perform(get("/executions").param("status", "COMPLETED").param("days", "5"))
                .andExpect(model().attribute("selectedDays", ""));

        verify(runStates, org.mockito.Mockito.times(2)).search(eq("COMPLETED"), eq(""), since.capture(), any());
        var weekAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        assertThat(since.getAllValues().get(0).toInstant())
                .isBetween(weekAgo.minusSeconds(60), weekAgo.plusSeconds(60));
        assertThat(since.getAllValues().get(1).toInstant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void executions_anEngineStatusOrAnUnknownStateListsEveryRun() throws Exception {
        when(runStates.search(eq(""), eq(""), any(), any())).thenReturn(emptyRows());

        mvc.perform(get("/executions").param("status", "FAILED_ESCALATED")).andExpect(status().isOk());
        mvc.perform(get("/executions").param("status", "NONSENSE")).andExpect(status().isOk());

        verify(runStates, org.mockito.Mockito.times(2)).search(eq(""), eq(""), any(), any());
    }

    // ----- detail: stepper state mapping -----

    @Test
    void execution_detail_mapsStepperStatesAndAttempts() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.AWAITING_HITL, 1,
                "{\"triage\":0,\"gate-1\":2}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("execution"))
                .andReturn();

        List<Map<String, Object>> steps = steps(result);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0))
                .containsEntry("state", "done")
                .containsEntry("label", "triage-worker")
                .containsEntry("attempts", 0);
        assertThat(steps.get(1))
                .containsEntry("state", "current")
                .containsEntry("label", "QA gate 1")
                .containsEntry("sublabel", "gate-1 · HITL_GATE")
                .containsEntry("attempts", 2);
        assertThat(steps.get(2))
                .containsEntry("state", "pending")
                .containsEntry("label", "finalizer-worker");
    }

    @Test
    void execution_detail_attachesTokensPerStepAndTotalsTheRun() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.AWAITING_HITL, 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "triage", "engine",
                        "{\"promptTokens\":734,\"completionTokens\":110}"),
                // A deterministic worker reports no usage: contributes nothing and
                // leaves its step without a token label.
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "finalize", "engine", "{}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_STARTED, "triage", "engine",
                        "{\"promptTokens\":999}")));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("tokenTotals", Map.of(
                        "present", true, "prompt", "734", "completion", "110", "total", "844")))
                .andReturn();

        List<Map<String, Object>> steps = steps(result);
        assertThat(steps.get(0)).containsEntry("tokens", "844 tokens (734 in / 110 out)");
        assertThat(steps.get(1)).containsEntry("tokens", null);
    }

    @Test
    void execution_detail_runWithoutLlmStepsReportsNoTokens() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.RUNNING, 0, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("tokenTotals", Map.of(
                        "present", false, "prompt", "0", "completion", "0", "total", "0")));
    }

    @Test
    void execution_detail_failedDeveloperRunUsesLatestStreamedCodingUsage() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"pi_usage\",\"inputTokens\":40,\"cacheReadTokens\":10,"
                                + "\"outputTokens\":3,\"costUsd\":0.1}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"pi_usage\",\"inputTokens\":80000,\"cacheReadTokens\":35000,"
                                + "\"cacheWriteTokens\":5000,\"outputTokens\":10000,\"costUsd\":0.3}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine", "{}")));

        var usage = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn())
                .usage();

        assertThat(usage.figures()).containsExactly(figure("Input", "80k"), figure("Cache read", "35k"),
                figure("Cache write", "5k"), figure("Output", "10k"));
        assertThat(usage.total()).isEqualTo("130k");
    }

    @Test
    void execution_detail_successfulDeveloperRunPrefersCompletedMetricsOverProgress() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"pi_usage\",\"inputTokens\":27,\"outputTokens\":8,\"costUsd\":0.3}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine",
                        "{\"inputTokens\":30,\"cacheReadTokens\":10,\"outputTokens\":9,"
                                + "\"promptTokens\":40,\"completionTokens\":9,\"costUsd\":0.4}")));

        var usage = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn())
                .usage();

        assertThat(usage.figures()).containsExactly(figure("Input", "30"), figure("Cache read", "10"),
                figure("Output", "9"));
        assertThat(usage.total()).isEqualTo("49");
    }

    @Test
    void execution_detail_estimatesDeveloperCostFromStoredRuntimeIdentity() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"pi_starting\",\"provider\":\"another-proxy\",\"model\":\"example-small\"}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"pi_usage\",\"inputTokens\":71200,\"cacheReadTokens\":2500000,"
                                + "\"cacheWriteTokens\":0,\"outputTokens\":35100,\"costUsd\":0}")));

        var run = run(mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("developer-run"))
                .andReturn());

        var cost = new DeveloperCostEstimator.Estimate(
                "0.10323", "USD", LocalDate.parse("2026-09-19"), "https://example.test/pricing").label();
        assertThat(run.usage().cost()).isEqualTo(cost);
        assertThat(run.status().amount()).isEqualTo(cost);
        assertThat(run.links().pricing()).containsExactly(
                new DeveloperRunView.PriceSource("https://example.test/pricing", " (2026-09-19)"));
    }

    @Test
    void priceSourceLinksOnlyHttpAddresses() {
        // Any other name stays text.
        assertThat(DeveloperRunView.PriceSource.of("docs.example.com (2026-09-19)"))
                .isEqualTo(new DeveloperRunView.PriceSource(null, "docs.example.com (2026-09-19)"));
        assertThat(DeveloperRunView.PriceSource.of("javascript:alert(1)").url()).isNull();
    }

    @Test
    void transcript_groupsEntriesByRunAndPairsEachToolCallWithItsResult() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        UUID first = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID second = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        var at = java.time.Instant.parse("2026-10-27T09:15:30Z");
        when(transcripts.read(EXECUTION_ID)).thenReturn(List.of(
                transcriptEntry(first, 0, "implement", "prompt", at, "{\"text\":\"Implement TASK-1\"}"),
                transcriptEntry(first, 1, "implement", "tool_call", at.plusSeconds(1),
                        "{\"id\":\"t1\",\"tool\":\"Bash\",\"input\":\"{ \\\"command\\\" : \\\"mvn test\\\" }\"}"),
                transcriptEntry(first, 2, "implement", "tool_result", at.plusSeconds(4),
                        "{\"id\":\"t1\",\"tool\":\"Bash\",\"error\":true,\"durationMs\":3000,\"text\":\"BUILD FAILURE\"}"),
                transcriptEntry(first, 3, "implement", "result", at.plusSeconds(5),
                        "{\"subtype\":\"success\",\"error\":false,\"text\":\"Done\",\"turns\":3,"
                                + "\"usage\":{\"inputTokens\":40,\"outputTokens\":5}}"),
                transcriptEntry(second, 0, "pr-follow-up", "note", at.plusSeconds(60),
                        "{\"text\":\"Transcript cut at 5 MB; the run went on.\"}")));

        var result = mvc.perform(get("/executions/{id}/transcript", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(view().name("transcript"))
                .andExpect(model().attributeExists("execution"))
                .andReturn();

        @SuppressWarnings("unchecked")
        var runs = (List<DeveloperTranscriptView.Section>) result.getModelAndView().getModel().get("runs");
        // A run without PR feedback state has no rounds to name, so its feedback section says only what it was.
        assertThat(runs).extracting(DeveloperTranscriptView.Section::label)
                .containsExactly("Implementation", "PR feedback");
        var entries = runs.getFirst().entries();
        assertThat(entries).extracting(DeveloperTranscriptView.Row::kind).containsExactly("prompt", "tool", "result");
        assertThat(entries.get(0).time()).isEqualTo(PageTime.clock(Instant.parse("2026-10-27T09:15:30Z")));
        assertThat(entries.get(1)).returns("Bash", DeveloperTranscriptView.Row::tool)
                .returns("{ \"command\" : \"mvn test\" }", DeveloperTranscriptView.Row::input)
                .returns("BUILD FAILURE", DeveloperTranscriptView.Row::result)
                .returns(true, DeveloperTranscriptView.Row::error)
                .returns("3 s", DeveloperTranscriptView.Row::duration)
                .returns(true, DeveloperTranscriptView.Row::hasResult);
        assertThat(entries.get(2).facts()).isEqualTo("3 turns · 40 input tokens · 5 output tokens");
    }

    private CodingTranscriptStore.Entry transcriptEntry(UUID run, int seq, String step, String kind,
                                                       java.time.Instant at, String detail) {
        return new CodingTranscriptStore.Entry(run, seq, step, kind, at, json.readTree(detail));
    }

    @Test
    void execution_detail_newRunShowsFullRetryUsageAndSafeFailureReason() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(artifact(
                "dev_coding_outcome.json", 1,
                "{\"status\":\"FAILED\",\"failure\":{\"code\":\"PROVIDER_RETRIES_EXHAUSTED\","
                        + "\"message\":\"Pi provider request failed: HTTP 429 (rate limited)\"}}", "dev-develop")));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"coding_usage\",\"inputTokens\":40000,\"cacheReadTokens\":100000,"
                                + "\"cacheWriteTokens\":0,\"outputTokens\":4000}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"provider_error\",\"reason\":\"HTTP 429 (rate limited)\"}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine",
                        "{\"inputTokens\":100000,\"cacheReadTokens\":200000,\"cacheWriteTokens\":0,"
                                + "\"outputTokens\":10000,\"provider\":\"openai-compatible\","
                                + "\"model\":\"example-small\"}")));

        var run = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn());

        assertThat(run.usage().figures()).containsExactly(figure("Input", "100k"), figure("Cache read", "200k"),
                figure("Cache write", "0"), figure("Output", "10k"));
        assertThat(run.usage().total()).isEqualTo("310k");
        assertThat(run.usage().cost()).isEqualTo(new DeveloperCostEstimator.Estimate(
                "0.026", "USD", LocalDate.parse("2026-09-19"), "https://example.test/pricing").label());
        assertThat(run.status().chipLabel()).isEqualTo("Failed");
        assertThat(run.status().headline()).isEqualTo("This run failed");
        assertThat(run.status().reason()).contains("HTTP 429 (rate limited)");
        assertThat(run.activity().rows()).extracting(DeveloperRunView.ActivityRow::target)
                .contains("Provider request failed · HTTP 429 (rate limited)");
    }

    @Test
    void execution_detail_missingRetryUsagePreservesTokensButSuppressesCost() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"coding_starting\",\"provider\":\"openai-compatible\","
                                + "\"model\":\"example-small\"}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "implement", "system",
                        "{\"activity\":\"coding_usage\",\"inputTokens\":1000,\"cacheReadTokens\":0,"
                                + "\"cacheWriteTokens\":0,\"outputTokens\":100,\"usageIncomplete\":true}")));

        var run = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn());

        assertThat(run.usage().cost()).isNull();
        assertThat(run.usage().note()).isEqualTo("Some usage was not reported, so the real cost may be higher.");
        assertThat(run.status().amount()).isNull();
        assertThat(run.usage().figures()).containsExactly(figure("Input", "1k"), figure("Cache read", "0"),
                figure("Cache write", "0"), figure("Output", "100"));
        assertThat(run.usage().total()).isEqualTo("1.1k");
    }

    @Test
    void execution_detail_developerUsageKeepsMissingCacheMetricsOptional() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine",
                        "{\"inputTokens\":20,\"outputTokens\":5,\"promptTokens\":20,"
                                + "\"completionTokens\":5}")));

        var usage = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn())
                .usage();

        assertThat(usage.figures()).containsExactly(figure("Input", "20"), figure("Output", "5"));
        assertThat(usage.total()).isEqualTo("25");
    }

    @Test
    void execution_detail_developerUsageDoesNotDoubleCountPartialNormalizedMetrics() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine",
                        "{\"promptTokens\":100,\"completionTokens\":20,\"cacheReadTokens\":40}")));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("steps", "tokenTotals"))
                .andReturn();

        assertThat(run(result).usage().figures()).containsExactly(figure("Cache read", "40"));
        assertThat(run(result).usage().total()).isEqualTo("120");
    }

    @Test
    void execution_detail_developerUsageIncludesLegacyOnlyStepsInTotal() throws Exception {
        PipelineExecution execution = developerExecution();
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "read-task", "engine",
                        "{\"promptTokens\":100,\"completionTokens\":20}"),
                new AuditEvent(EXECUTION_ID, AuditEventType.STEP_COMPLETED, "implement", "engine",
                        "{\"inputTokens\":30,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"outputTokens\":9,"
                                + "\"promptTokens\":30,\"completionTokens\":9,\"provider\":\"openai-compatible\","
                                + "\"model\":\"example-small\"}")));

        var usage = run(mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn())
                .usage();

        assertThat(usage.figures()).containsExactly(figure("Input", "30"), figure("Cache read", "0"),
                figure("Cache write", "0"), figure("Output", "9"));
        assertThat(usage.total()).isEqualTo("159");
        assertThat(usage.cost()).isNull();
    }

    // ----- run page: polling while the run or its attempt is in flight -----

    @Test
    void execution_detail_runThatEndedPollsWhileItsAttemptIsStillInFlight() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        when(taskQuery.runContext(EXECUTION_ID)).thenReturn(Optional.of(runContext("In progress", true)));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pollUrl", "/executions/" + EXECUTION_ID + "/poll"))
                .andExpect(model().attributeExists("pollBaseline"));
    }

    @Test
    void execution_detail_runWhoseAttemptOpenedItsPullRequestDoesNotPoll() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        when(taskQuery.runContext(EXECUTION_ID)).thenReturn(Optional.of(runContext("Draft PR #7", false)));

        var run = run(mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pollUrl", (Object) null))
                .andReturn());

        assertThat(run.status().chipLabel()).isEqualTo("Draft PR #7");
        assertThat(run.header().pageTitle()).isEqualTo("TASK-1 · run 2");
        assertThat(run.header().crumb()).isEqualTo("Run 2");
    }

    @Test
    void poll_revisionChangesWhenTheAttemptEndsAfterTheRun() throws Exception {
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(developerExecution()));
        when(taskQuery.runContext(EXECUTION_ID)).thenReturn(Optional.of(runContext("In progress", true)));
        var page = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andReturn();
        var baseline = page.getModelAndView().getModel().get("pollBaseline");

        var unchanged = mvc.perform(get("/executions/{id}/poll", EXECUTION_ID)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        when(taskQuery.runContext(EXECUTION_ID)).thenReturn(Optional.of(runContext("Draft PR #7", false)));
        var ended = mvc.perform(get("/executions/{id}/poll", EXECUTION_ID)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(unchanged).isEqualTo("{\"revision\":\"" + baseline + "\"}");
        assertThat(ended).doesNotContain(baseline.toString()).startsWith("{\"revision\":\"");
    }

    @Test
    void execution_detail_nonDeveloperRunIgnoresPiProgress() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.RUNNING, 0, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        when(auditLog.forExecution(EXECUTION_ID)).thenReturn(List.of(
                new AuditEvent(EXECUTION_ID, AuditEventType.RUNTIME_PROGRESS, "triage", "system",
                        "{\"activity\":\"pi_usage\",\"promptTokens\":27,\"completionTokens\":8,\"costUsd\":0.3}")));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("tokenTotals", Map.of(
                        "present", false, "prompt", "0", "completion", "0", "total", "0")));
    }

    @Test
    void execution_detail_escalatedCurrentStepMarkedFailed() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.FAILED_ESCALATED, 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        assertThat(steps(result).get(1)).containsEntry("state", "failed");
    }

    @Test
    void execution_detail_escalatedSurfacesPendingReview() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.FAILED_ESCALATED, 1, "{}");
        HitlReview pending = new HitlReview(EXECUTION_ID, "escalation", 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(reviews.findByExecutionIdOrderByCreatedAtAsc(EXECUTION_ID)).thenReturn(List.of(pending));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingReview", pending));
    }

    @Test
    void execution_detail_noStepperWhenFlowUnregistered() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.RUNNING, 0, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.empty());

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        assertThat(steps(result)).isEmpty();
    }

    // ----- detail: artifact version grouping -----

    @Test
    void execution_detail_groupsArtifactsByNameWithLatestFlagged() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.COMPLETED, 3, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(artifactStore.allForExecution(EXECUTION_ID)).thenReturn(List.of(
                artifact("coverage.md", 1, "cov", "test-exec"),
                artifact("test_plan.md", 1, "v1 body", "test-spec"),
                artifact("test_plan.md", 2, "v2 body", "qa")));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID)).andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("artifactGroups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("name")).isEqualTo("coverage.md");
        assertThat(groups.get(1).get("name")).isEqualTo("test_plan.md");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> planVersions = (List<Map<String, Object>>) groups.get(1).get("versions");
        assertThat(planVersions).hasSize(2);
        assertThat(planVersions.get(0)).containsEntry("version", 1).containsEntry("latest", false);
        assertThat(planVersions.get(1)).containsEntry("version", 2).containsEntry("latest", true);
        assertThat(planVersions.get(1).get("label").toString()).contains("v2").contains("qa");
        assertThat(planVersions.get(0).get("panelId")).isNotEqualTo(planVersions.get(1).get("panelId"));
    }

    // ----- detail: pending review / poll url -----

    @Test
    void execution_detail_awaitingHitlSurfacesPendingReviewAndPolls() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.AWAITING_HITL, 1, "{}");
        HitlReview pending = new HitlReview(EXECUTION_ID, "qa-1", 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));
        when(reviews.findByExecutionIdOrderByCreatedAtAsc(EXECUTION_ID)).thenReturn(List.of(pending));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pendingReview", pending))
                .andExpect(model().attribute("pollUrl", "/api/executions/" + EXECUTION_ID));
    }

    @Test
    void execution_detail_terminalRunDoesNotPoll() throws Exception {
        PipelineExecution execution = execution(ExecutionStatus.COMPLETED, 3, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(execution));

        mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pollUrl", (Object) null));
    }

    // ----- detail: sub-flow child rows -----

    @Test
    void execution_detail_childRowsCarryStepLabelWithNullFallback() throws Exception {
        PipelineExecution parent = execution(ExecutionStatus.AWAITING_SUBFLOW, 1, "{}");
        when(executions.findById(EXECUTION_ID)).thenReturn(Optional.of(parent));
        when(flowRegistry.find("test-factory")).thenReturn(Optional.of(descriptor()));
        when(executions.findByParentExecutionId(EXECUTION_ID)).thenReturn(List.of(
                child(UUID.fromString("00000000-0000-0000-0000-000000000002"), 1),
                child(UUID.fromString("00000000-0000-0000-0000-000000000003"), 99)));

        var result = mvc.perform(get("/executions/{id}", EXECUTION_ID))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("children");
        assertThat(children).hasSize(2);
        assertThat(children.get(0))
                .containsEntry("flowId", "child-flow")
                .containsEntry("status", ExecutionStatus.RUNNING)
                .containsEntry("stepLabel", "step 1 · QA gate 1");
        assertThat(children.get(0).get("idShort").toString()).isNotBlank();
        assertThat(children.get(1))
                .as("out-of-range parent step index must fall back to a null label, not throw")
                .containsEntry("stepLabel", null);
    }

    @Test
    void executions_baseUrl_urlEncodesFilterValues() throws Exception {
        when(runStates.search(eq(""), eq("a b&c"), any(), any())).thenReturn(emptyRows());

        var result = mvc.perform(get("/executions").param("flow", "a b&c"))
                .andExpect(status().isOk())
                .andReturn();

        String baseUrl = (String) result.getModelAndView().getModel().get("baseUrl");
        assertThat(baseUrl).contains("flow=a+b%26c").doesNotContain("a b&c");
    }

    // ----- helpers -----

    private static Page<RunStateRepository.Row> emptyRows() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    private record Row(UUID getId, String getState) implements RunStateRepository.Row {
    }

    private static List<Map<String, Object>> steps(org.springframework.test.web.servlet.MvcResult result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) result.getModelAndView().getModel().get("steps");
        return steps;
    }

    private static DeveloperRunView run(org.springframework.test.web.servlet.MvcResult result) {
        return (DeveloperRunView) result.getModelAndView().getModel().get("run");
    }

    private static DeveloperRunView.Figure figure(String label, String value) {
        return new DeveloperRunView.Figure(label, value);
    }

    private static RunContext runContext(String chip, boolean inFlight) {
        return new RunContext(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), "TASK-1", "Fix route", null,
                2, "example-large · max", new AttemptStatus(chip, "chip chip-info", null, chip), null, null, null,
                null, null, null, inFlight, null, null);
    }

    private static PipelineExecution execution(ExecutionStatus status, int stepIndex, String retryCounts) {
        PipelineExecution execution = new PipelineExecution("test-factory", "1", "{}");
        execution.setStatus(status);
        execution.setCurrentStepIndex(stepIndex);
        execution.setRetryCounts(retryCounts);
        return execution;
    }

    private static PipelineExecution developerExecution() {
        PipelineExecution execution = new PipelineExecution("dev-factory", "0.6.0", "{}");
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setCurrentStepIndex(6);
        try {
            var field = PipelineExecution.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(execution, EXECUTION_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return execution;
    }

    private static PipelineExecution child(UUID id, int parentStepIndex) {
        PipelineExecution child = new PipelineExecution("child-flow", "1", "{}");
        child.setStatus(ExecutionStatus.RUNNING);
        child.setParentExecutionId(EXECUTION_ID);
        child.setParentStepIndex(parentStepIndex);
        try {
            var field = PipelineExecution.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(child, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return child;
    }

    private static Artifact artifact(String name, int version, String content, String createdBy) {
        return new Artifact(EXECUTION_ID, name, version, "text/markdown", content, "sha", createdBy);
    }

    private static FlowDescriptor descriptor() {
        StepDescriptor triage = new StepDescriptor("triage", StepType.AGENT, "triage-worker",
                null, null, null, null, null);
        StepDescriptor gate = new StepDescriptor("gate-1", StepType.HITL_GATE, null,
                new HitlGateSpec("qa-1", "QA gate 1", "instructions", null), null, null, null, null);
        StepDescriptor finalizer = new StepDescriptor("finalizer", StepType.AGENT, "finalizer-worker",
                null, null, null, null, null);
        return new FlowDescriptor("test-factory", "Test Factory", "1", null, null, null,
                List.of(triage, gate, finalizer), null);
    }
}
