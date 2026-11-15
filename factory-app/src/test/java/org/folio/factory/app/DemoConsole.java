package org.folio.factory.app;

import org.folio.factory.app.support.SharedDatabase;
import org.folio.factory.app.tasks.TaskFixture;
import org.folio.factory.app.tasks.execution.StopReasons;
import org.folio.factory.app.tasks.persistence.Task;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.app.tasks.persistence.TaskAttemptRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskRepository;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.AttemptRateSnapshot;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.HitlReview;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.engine.HitlGateOpener;
import org.folio.factory.core.repository.HitlReviewRepository;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.feedback.FeedbackArtifacts;
import org.folio.factory.devfactory.feedback.FeedbackState;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.TokenUsage;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.verification.VerificationFailure;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;
import org.folio.factory.devfactory.worker.VerifyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The console on a fixed port with one task in every state a developer meets, for a walk through the pages in a
 * real browser. It is not a test and its name keeps it out of the build: start it by hand with
 * {@code mvn -pl factory-app -am test -Dtest=DemoConsole -Ddemo.console=true -Dsurefire.failIfNoSpecifiedTests=false}
 * and open http://localhost:8090/flows/dev-factory. It uses a throwaway database and a stopped engine, so no
 * seeded task ever runs and the local Factory is not touched. Stop it with Ctrl-C.
 *
 * <p>The states: ready to run (from a scan, added by key, in progress in Jira with a two-line title, withdrawn
 * before the start), cannot run (no repository, several repositories), queued (two waiting, one starting), working,
 * asked a question, stopped for each cause
 * Factory names (checks failed on tests, code that does not compile, checks that could not run, a base branch that
 * does not build and spent nothing, a step the engine gave up on after three tries, time limit, turn limit,
 * whole-attempt deadline, shared configuration, a pull request GitHub refused, a coding step that Factory
 * repeated, changes that held the API key of a model), three attempts
 * that end in a pull request, a task no longer assigned to the developer, a draft pull request without and with
 * new comments, a pull request ready for review, merged, closed without merge, a feedback round that works now,
 * one that asked a question, one whose checks failed and one whose changes held the API key of a model, and a run
 * whose end Factory could not confirm, with and without the queue held and after it opened a pull request.
 *
 * <p>Every run that reached the agent reports its usage the way the coding step does, with the model, the effort
 * and the price from config/developer-models.yaml (two models, three efforts), so every finished attempt has a
 * cost and the Dashboard shows the Developer Flow spending: the task delivered on the third attempt failed twice
 * first, and the task stopped at the time limit is the most expensive one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=8090")
@Import({StubLlmConfiguration.class, SharedDatabase.class})
@TestPropertySource(properties = {
        "factory.connectors.jira.base-url=https://jira.example.org",
        "factory.tasks.source.assignee=Jane Developer",
        "FACTORY_ZAI_API_KEY=demo-console-key",
        // Templates, CSS and JS come from src without a cache: an edit shows on reload, and a Maven build that
        // rewrites target/classes does not touch them.
        "spring.thymeleaf.prefix=file:src/main/resources/templates/",
        "spring.thymeleaf.cache=false",
        "factory.console.static-location=file:src/main/resources/static/",
        "spring.web.resources.chain.cache=false"})
@EnabledIfSystemProperty(named = "demo.console", matches = "true")
class DemoConsole {

    private static final String ELIGIBLE = "repository mod-bar";
    private static final String PULL = "https://github.com/folio-org/mod-bar/pull/";
    /** The file the agent wrote a model's API key into, in the seeds of that stop. */
    private static final String KEY_FILE = "src/main/resources/application.yml";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The model and effort of each seeded run, in turn. */
    private static final List<CodingRequest.RunSettings> RUNS = List.of(
            new CodingRequest.RunSettings("glm-5.3", "max", null),
            new CodingRequest.RunSettings("glm-5.3-flash", "high", null),
            new CodingRequest.RunSettings("glm-5.3", "low", null));

    @Autowired
    private PipelineExecutionRepository executions;
    @Autowired
    private HitlReviewRepository reviews;
    @Autowired
    private ArtifactStore artifacts;
    @Autowired
    private AuditLog auditLog;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskAttemptRepository attempts;
    @Autowired
    private TaskInstallationRepository installations;
    @Autowired
    private AttemptRateSnapshot rates;
    @Autowired
    private JdbcTemplate jdbc;

    private final Map<UUID, CodingRequest.RunSettings> settingsByRun = new HashMap<>();

    @Test
    void serveEveryState() throws InterruptedException {
        readyToRun();
        queued();
        working();
        askedAQuestion();
        failed();
        startingBuildFailed();
        engineGaveUp();
        stoppedByLimits();
        threeAttempts();
        noLongerAssigned();
        pullRequest("80007", "MODBAR-807", "Pull request without new comments", 7, false);
        pullRequest("80008", "MODBAR-808", "Pull request with a new comment", 8, true);
        readyForReview();
        merged();
        closedWithoutMerge();
        roundWorking();
        roundAsked();
        roundFailed();
        roundKeyInChanges();
        endNotConfirmed();
        System.out.println("Demo console: http://localhost:8090/flows/dev-factory");
        Thread.sleep(Duration.ofHours(8));
    }

    private void readyToRun() {
        task("80001", "MODBAR-801", "Ready task from the Jira scan");
        var byKey = task("80002", "MODBAR-802", "Ready task added by key");
        byKey.setAddedByKey(true);
        tasks.save(byKey);
        tasks.save(TaskFixture.task("MODBAR", "80028", "MODBAR-828", "Ready task that is in progress in Jira and has a"
                        + " title long enough to take two lines when the window is narrow",
                "{\"key\": \"MODBAR-828\", \"status\": \"In Progress\"}", Task.Eligibility.ELIGIBLE, ELIGIBLE));
        tasks.save(TaskFixture.task("KEYCLOAK", "80003", "KEYCLOAK-803", "Task of a project without a repository",
                "{\"key\": \"KEYCLOAK-803\", \"status\": \"Open\"}", Task.Eligibility.INELIGIBLE,
                "Project KEYCLOAK is not configured: no repository for it in config/developer-repositories.yaml"));
        tasks.save(TaskFixture.task("FOLIO", "80024", "FOLIO-824", "Task of a project with several repositories",
                "{\"key\": \"FOLIO-824\", \"status\": \"In Progress\"}", Task.Eligibility.INELIGIBLE,
                TaskRoutingReason.severalRepositories("FOLIO", List.of("mod-bar", "mod-foo"))));
        attempt(task("80022", "MODBAR-822", "Task withdrawn before it started"), TaskAttempt.State.WITHDRAWN, null,
                null);
    }

    private void queued() {
        attempt(task("80004", "MODBAR-804", "Queued task, first in the queue"), TaskAttempt.State.RESERVED, null,
                null);
        attempt(task("80027", "MODBAR-827", "Queued task, second in the queue"), TaskAttempt.State.RESERVED, null,
                null);
        var starting = executions.save(new PipelineExecution("dev-factory", "0.6.0", "{}"));
        attempt(task("80026", "MODBAR-826", "Task whose run starts now"), TaskAttempt.State.QUEUED, null, starting);
    }

    private void working() {
        var execution = bareExecution("0.6.0", ExecutionStatus.RUNNING, 3);
        usage(execution, AuditEventType.RUNTIME_PROGRESS, 1);
        attempt(task("80005", "MODBAR-805", "Task the agent works on now"), TaskAttempt.State.RUNNING, null,
                execution);
        auditLog.record(execution.getId(), AuditEventType.STEP_STARTED, "implement", Map.of());
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_start", "tool", "Read", "path", "/workspace/src/Foo.java"));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_end", "tool", "Read", "path", "/workspace/src/Foo.java",
                        "error", false, "durationMs", 6));
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "implement",
                Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn -B -ntp test"));
    }

    private void askedAQuestion() {
        var execution = execution("0.6.0", ExecutionStatus.COMPLETED, 10);
        attempt(task("80006", "MODBAR-806", "Task where the agent asked a question"), TaskAttempt.State.STOPPED,
                "clarify-implementation: " + TaskStops.DECISION_REASON + "Remove the old routes too?", execution);
        artifacts.putMarkdown(execution.getId(), "dev_coding_outcome.json", """
                {"status":"NEEDS_DECISION","summary":"I read the code.","decision":{"kind":"PRODUCT_REQUIREMENTS",
                "question":"Remove the old routes too?","options":["Only from now on.","Also the old ones."],
                "evidence":"removeRoutes runs only on a new event."},"metrics":{}}""", "implement");
        // The workers after the question record that they did not run, so the run page does not show them as passed.
        put(execution, VerifyWorker.RECEIPT, Map.of("state", "NOT_RUN"));
        put(execution, DeliveryWorker.DELIVERY, Map.of("state", "NOT_RUN"));
    }

    /** The checks failed on tests, failed to compile, or could not run. */
    private void failed() {
        checksFailed("80009", "Task whose checks failed", "\"testCount\":300,\"failureCount\":2,\"errorCount\":0,"
                + "\"failureKind\":\"UNKNOWN\",\"exitCode\":1");
        checksFailed("80029", "Task whose code does not compile", "\"failureKind\":\"CANDIDATE_COMPILE\",\"exitCode\":1");
        checksFailed("80030", "Task whose checks could not run", "\"failureKind\":\"ENVIRONMENT\",\"exitCode\":-1");
    }

    /** The verification result in the shape VerifyWorker writes, and the reason Factory builds from it. */
    private TaskAttempt checksFailed(String issueId, String title, String fields) {
        var execution = execution("0.6.0", ExecutionStatus.COMPLETED, 10);
        var result = "{\"state\":\"" + StopReasons.CHECKS_FAILED + "\"," + fields + "}";
        artifacts.put(execution.getId(), VerifyWorker.RESULT, result, "application/json", "demo");
        return attempt(task(issueId, "MODBAR-8" + issueId.substring(3), title), TaskAttempt.State.STOPPED,
                StopReasons.ofResult(JSON.readTree(result)), execution);
    }

    /** One task for each cause of a stop that Factory names, besides failed checks. */
    private void stoppedByLimits() {
        // The limits are the shipped defaults: 60 minutes per step and 400 turns.
        var timeLimit = bareExecution("0.6.0", ExecutionStatus.COMPLETED, 10);
        usage(timeLimit, AuditEventType.STEP_COMPLETED, 10);
        attempt(task("80017", "MODBAR-817", "Task stopped at the time limit"), TaskAttempt.State.STOPPED,
                "DEVELOPMENT_FAILED: " + TaskStops.timeLimitReason(60), timeLimit);
        stopped("80018", "Task stopped at the turn limit", "DEVELOPMENT_FAILED: " + TaskStops.turnLimitReason(400));
        deadlineBeforeVerification();
        stopped("80019", "Task stopped by the shared configuration", "DEVELOPMENT_FAILED: Coding runtime"
                + " configuration is incomplete or unsupported; check Developer Flow configuration.");
        pullRequestRefused();
        codingRepeated();
        keyInChanges();
    }

    /**
     * The whole-attempt time ran out before the verify step: the task guard stores its reason, VerifyWorker records
     * that no check ran, and DeliveryWorker that nothing was delivered.
     */
    private void deadlineBeforeVerification() {
        var reason = TaskStops.deadlineReason("verification");
        var execution = stopped("80020", "Task stopped at the whole-attempt deadline", "verify: " + reason);
        put(execution, VerifyWorker.RECEIPT, Map.of("state", "NOT_RUN", "reason", reason));
        put(execution, VerifyWorker.RESULT, Map.of("state", TaskStops.DEADLINE_STATE, "reason", reason));
        put(execution, DeliveryWorker.DELIVERY, Map.of("state", "NOT_RUN"));
    }

    /** Factory pushed the branch, then GitHub refused the pull request with an error the engine does not retry. */
    private void pullRequestRefused() {
        var blocked = Map.of("state", "DELIVERY_BLOCKED", "reason", DeliveryWorker.pullRequestRefusedReason(403));
        var execution = stopped("80035", "Task whose pull request GitHub refused",
                StopReasons.ofResult(JSON.valueToTree(blocked)));
        put(execution, DeliveryWorker.DELIVERY, blocked);
        put(execution, VerifyWorker.RESULT, blocked);
    }

    /** The engine repeated the coding step after the paid start, so the step stopped before the agent could start. */
    private void codingRepeated() {
        var replayed = Map.of("state", "PAID_START_CONSUMED", "reason", TaskStops.REPLAYED_CODING_REASON);
        var execution = stopped("80032", "Task whose coding step Factory repeated",
                "implement: " + TaskStops.REPLAYED_CODING_REASON);
        put(execution, DevelopWorker.CANDIDATE, replayed);
        put(execution, VerifyWorker.RESULT, replayed);
    }

    /** The agent wrote the API key of a model into a file, so Factory did not keep its changes. */
    private void keyInChanges() {
        var refused = Map.of("state", "DEVELOPMENT_FAILED", "reason", TaskStops.secretInChangesReason(KEY_FILE));
        var execution = stopped("80036", "Task whose changes held the API key of a model",
                StopReasons.ofResult(JSON.valueToTree(refused)));
        put(execution, DevelopWorker.CANDIDATE, refused);
        put(execution, VerifyWorker.RESULT, refused);
    }

    /** The base branch did not build, so the agent never started and the attempt spent nothing. */
    private void startingBuildFailed() {
        var execution = bareExecution("0.6.0", ExecutionStatus.COMPLETED, 10);
        artifacts.put(execution.getId(), "dev_readiness.json", "{\"state\":\"BASELINE_FAILED\"}", "application/json",
                "demo");
        attempt(task("80031", "MODBAR-831", "Task whose base branch does not build"), TaskAttempt.State.STOPPED,
                "BLOCKED_ENVIRONMENT: " + TaskStops.startingBuildFailedReason(""), execution);
    }

    /** GitHub answered 429 on each try to open the pull request, so the engine gave up and opened its review. */
    private void engineGaveUp() {
        var error = "429 Too Many Requests: \"{\"message\": \"API rate limit exceeded\"}\"";
        var execution = execution(FeedbackArtifacts.VERSION, ExecutionStatus.FAILED_ESCALATED, 7);
        execution.setErrorMessage(error);
        executions.save(execution);
        reviews.save(new HitlReview(execution.getId(), HitlGateOpener.ESCALATION_GATE_ID, 7, "{}"));
        attempt(task("80033", "MODBAR-833", "Task whose pull request GitHub refused three times"),
                TaskAttempt.State.STOPPED, StopReasons.engineGaveUp("publish", 3, error), execution);
    }

    private PipelineExecution stopped(String issueId, String title, String reason) {
        var execution = execution("0.6.0", ExecutionStatus.COMPLETED, 10);
        attempt(task(issueId, "MODBAR-8" + issueId.substring(3), title), TaskAttempt.State.STOPPED, reason,
                execution);
        return execution;
    }

    /** The artifact a worker writes under this name, with the fields the pages read from it. */
    private void put(PipelineExecution execution, String name, Map<String, String> content) {
        artifacts.put(execution.getId(), name, JSON.writeValueAsString(content), "application/json", "demo");
    }

    /** The agent changed no file, then printed too much, and the third attempt opened a draft pull request. */
    private void threeAttempts() {
        var task = task("80021", "MODBAR-821", "Task delivered on the third attempt");
        attempt(task, 1, TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.NO_CHANGES_REASON,
                execution("0.6.0", ExecutionStatus.COMPLETED, 10));
        attempt(task, 2, TaskAttempt.State.STOPPED, "DEVELOPMENT_FAILED: " + TaskStops.OUTPUT_LIMIT_REASON,
                execution("0.6.0", ExecutionStatus.COMPLETED, 10));
        var execution = execution(FeedbackArtifacts.VERSION, ExecutionStatus.AWAITING_HITL, 9);
        deliver(attempt(task, 3, TaskAttempt.State.PR_READY, null, execution), 21, true);
        waitForComments(execution, 21);
    }

    /** A failed task that a Jira scan no longer finds among the developer's tasks. */
    private void noLongerAssigned() {
        var attempt = checksFailed("80025", "Failed task that is no longer assigned to you",
                "\"testCount\":300,\"failureCount\":1,\"errorCount\":0,\"failureKind\":\"UNKNOWN\",\"exitCode\":1");
        var task = tasks.findById(attempt.getTaskId()).orElseThrow();
        task.setInScope(false);
        tasks.save(task);
    }

    /** A delivered task that waits for PR comments after two rounds; {@code fresh} adds a comment no round fixed. */
    private void pullRequest(String issueId, String key, String title, int number, boolean fresh) {
        var execution = delivered(issueId, key, title, number, true, ExecutionStatus.AWAITING_HITL);
        var id = execution.getId();
        var done = comment("inline:1", "reviewer", "Add minProperties to the items object.", "a.json", 3, number);
        rounds(id, pr(number, true), done);
        comments(id, number, fresh ? done + "," + comment("inline:2", "jane",
                "Rename the test so it says what it checks.", "AppTest.java", 214, number) : done);
        review(execution);
        auditLog.record(id, AuditEventType.CONNECTOR_ACTION, "pr-follow-up", Map.of("action", "pr-round-comment",
                "round", 1, "commentUrl", PULL + number + "#issuecomment-9"));
    }

    /** A pull request that passed every check, so it is not a draft, and has no comment yet. */
    private void readyForReview() {
        waitForComments(delivered("80011", "MODBAR-811", "Pull request ready for review", 11, false,
                ExecutionStatus.AWAITING_HITL), 11);
    }

    private void merged() {
        var execution = delivered("80012", "MODBAR-812", "Task whose pull request is merged", 12, false,
                ExecutionStatus.COMPLETED);
        state(execution.getId(), "\"round\":0,\"pr\":" + pr(12, true) + ",\"phase\":\"READY\"");
        state(execution.getId(), "\"round\":0,\"pr\":" + pr(12, false) + ",\"phase\":\"FINISHED\","
                + "\"completionReason\":\"MERGED\",\"taskSummary\":\"PR merged\","
                + "\"finishedAt\":\"2026-11-10T19:02:00Z\"");
        comments(execution.getId(), 12, "");
    }

    private void closedWithoutMerge() {
        var execution = delivered("80013", "MODBAR-813", "Task whose pull request was closed without merge", 13,
                true, ExecutionStatus.CANCELLED);
        state(execution.getId(), "\"round\":0,\"pr\":" + pr(13, true) + ",\"phase\":\"READY\"");
        state(execution.getId(), "\"round\":0,\"pr\":" + pr(13, false) + ",\"phase\":\"STOPPED\","
                + "\"completionReason\":\"CLOSED_WITHOUT_MERGE\",\"taskSummary\":\"PR closed without merge\","
                + "\"reason\":\"PR closed without merge\"");
        comments(execution.getId(), 13, "");
    }

    /** The agent works on the first feedback round now. */
    private void roundWorking() {
        var execution = delivered("80014", "MODBAR-814", "Pull request whose feedback round works now", 14, true,
                ExecutionStatus.RUNNING);
        var selected = comment("inline:1", "reviewer", "Check the tenant id before the lookup.", "Foo.java", 41, 14);
        state(execution.getId(), roundStart(14, selected) + ",\"phase\":\"CODE\"");
        comments(execution.getId(), 14, selected);
        startedMinutesAgo(execution.getId(), "pr-follow-up", 4);
        auditLog.record(execution.getId(), AuditEventType.RUNTIME_PROGRESS, "pr-follow-up",
                Map.of("activity", "tool_execution_start", "tool", "bash", "command", "mvn -B -ntp test"));
    }

    /** The agent asked a question in the first feedback round. */
    private void roundAsked() {
        var execution = delivered("80015", "MODBAR-815", "Pull request where the agent asked about a comment", 15,
                true, ExecutionStatus.AWAITING_HITL);
        var selected = comment("inline:1", "reviewer", "Should this also cover consortium tenants?", "Foo.java", 77,
                15);
        state(execution.getId(), roundStart(15, selected) + ",\"phase\":\"CODE\"");
        state(execution.getId(), roundStart(15, selected) + ",\"phase\":\"QUESTION\","
                + "\"failureKind\":\"PRODUCT_REQUIREMENTS\",\"question\":\"Cover consortium tenants too?\","
                + "\"options\":[\"Only single tenants.\",\"Consortium tenants too.\"]");
        comments(execution.getId(), 15, selected);
        review(execution);
    }

    /** The independent checks failed after the agent changed the code in the first feedback round. */
    private void roundFailed() {
        var execution = delivered("80016", "MODBAR-816", "Pull request whose feedback round failed its checks", 16,
                true, ExecutionStatus.AWAITING_HITL);
        var selected = comment("inline:1", "reviewer", "Use the shared date format.", "Bar.java", 12, 16);
        var checks = oneTestFailed(execution);
        state(execution.getId(), roundStart(16, selected) + ",\"phase\":\"CODE\"");
        state(execution.getId(), roundStart(16, selected) + ",\"phase\":\"FAILURE\",\"failureKind\":\""
                + checks.failureKind() + "\",\"reason\":" + FeedbackArtifacts.JSON.writeValueAsString(
                checks.failureReason()) + ",\"verification\":" + FeedbackArtifacts.JSON.writeValueAsString(checks));
        comments(execution.getId(), 16, selected);
        review(execution);
    }

    /** The agent wrote the API key of a model into a file in the first feedback round, so its edits were not saved. */
    private void roundKeyInChanges() {
        var execution = delivered("80037", "MODBAR-837",
                "Pull request whose feedback round wrote the API key of a model", 37, true, ExecutionStatus.AWAITING_HITL);
        var selected = comment("inline:1", "reviewer", "Read the timeout from the configuration.", "application.yml", 8,
                37);
        var reason = FeedbackState.EDITS_NOT_SAVED_START + TaskStops.secretInChangesCause(KEY_FILE);
        state(execution.getId(), roundStart(37, selected) + ",\"phase\":\"CODE\"");
        state(execution.getId(), roundStart(37, selected) + ",\"phase\":\"FAILURE\",\"resumePhase\":\"CODE\","
                + "\"failureKind\":\"CODING\",\"reason\":" + FeedbackArtifacts.JSON.writeValueAsString(reason));
        comments(execution.getId(), 37, selected);
        review(execution);
    }

    /** The checks of a feedback round in which the only test failed, as the verifier returns them. */
    private static VerificationReceipt oneTestFailed(PipelineExecution execution) {
        var now = Instant.now();
        return new VerificationReceipt(execution.getId().toString(), "mod-bar", "a".repeat(40), "b".repeat(40),
                "c".repeat(64), "java21-unit", "maven", List.of("mvn", "-B", "-ntp", "test"), "checks", now, now, 1, 1,
                1, 1, 0, "FAIL", "BarTest.formatsTheDate: expected \"2026-11-10\" but was \"10/11/2026\"",
                VerificationFailure.CANDIDATE, 0, List.of(), List.of());
    }

    /** A task with one attempt that opened pull request {@code number}; its run has this engine status. */
    private PipelineExecution delivered(String issueId, String key, String title, int number, boolean draft,
                                        ExecutionStatus status) {
        var execution = execution(FeedbackArtifacts.VERSION, status, 9);
        deliver(attempt(task(issueId, key, title), TaskAttempt.State.PR_READY, null, execution), number, draft);
        return execution;
    }

    private void deliver(TaskAttempt attempt, int number, boolean draft) {
        attempt.setPublicationIdentity(PULL + number);
        attempt.setDraftReasons(draft ? "[\"1 acceptance criterion is not met.\"]" : null);
        attempts.save(attempt);
        artifacts.put(attempt.getExecutionId(), "dev_delivery.json", "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\""
                + PULL + number + "\",\"pullRequestDraft\":" + draft + "}", "application/json", "demo");
    }

    /** No round yet and no comment on the pull request: the run waits for the first review. */
    private void waitForComments(PipelineExecution execution, int number) {
        state(execution.getId(), "\"round\":0,\"pr\":" + pr(number, true) + ",\"phase\":\"READY\"");
        comments(execution.getId(), number, "");
        review(execution);
    }

    private void state(java.util.UUID id, String fields) {
        artifacts.put(id, "dev_pr_state.json", "{" + fields + "}", "application/json", "demo");
    }

    private void comments(java.util.UUID id, int number, String comments) {
        artifacts.put(id, "dev_pr_comments.json", "{\"id\":\"s1\",\"pr\":" + pr(number, true) + ",\"comments\":["
                + comments + "]}", "application/json", "demo");
    }

    private void review(PipelineExecution execution) {
        reviews.save(new HitlReview(execution.getId(), "pr-feedback", execution.getCurrentStepIndex(), "{}"));
    }

    private static String roundStart(int number, String selected) {
        return "\"round\":1,\"pr\":" + pr(number, true) + ",\"selected\":[" + selected + "],\"roundStartedAt\":\""
                + Instant.now().minus(Duration.ofMinutes(4)) + "\"";
    }

    private static String pr(int number, boolean open) {
        return "{\"repository\":\"folio-org/mod-bar\",\"number\":" + number + ",\"branch\":\"factory/x\","
                + "\"baseBranch\":\"master\",\"headSha\":\"" + "c".repeat(40) + "\",\"url\":\"" + PULL + number
                + "\",\"open\":" + open + "}";
    }

    /** Round 1 changed a file and passed its checks; round 2 changed nothing. */
    private void rounds(java.util.UUID id, String pr, String done) {
        var first = "\"round\":1,\"pr\":" + pr + ",\"selected\":[" + done + "],\"roundStartedAt\":\"2026-11-10T18:22:00Z\"";
        var checks = "{\"executionId\":\"" + id + "\",\"argv\":[],\"exitCode\":0,\"surefireReportCount\":1,"
                + "\"testCount\":300,\"failureCount\":0,\"errorCount\":0,\"result\":\"PASS\",\"output\":\"BUILD SUCCESS\","
                + "\"failsafeReportCount\":0}";
        artifacts.put(id, "dev_pr_state.json", "{" + first + ",\"phase\":\"CODE\"}", "application/json", "demo");
        artifacts.put(id, "dev_pr_state.json", "{" + first + ",\"phase\":\"READY\",\"commitSha\":\"" + "c".repeat(40)
                + "\",\"summary\":\"## Changes\\n- Added `minProperties` to the items object.\\n\\n## Assumptions\\n"
                + "- None.\",\"finishedAt\":\"2026-11-10T18:28:00Z\",\"runtimeName\":\"Pi\",\"verification\":" + checks
                + "}", "application/json", "demo");
        var second = "\"round\":2,\"pr\":" + pr + ",\"selected\":[" + done + "],\"roundStartedAt\":\"2026-11-10T18:40:00Z\"";
        artifacts.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"CODE\"}", "application/json", "demo");
        artifacts.put(id, "dev_pr_state.json", "{" + second + ",\"phase\":\"READY\",\"commitSha\":\"\",\"summary\":"
                + "\"The agent changed no file.\",\"finishedAt\":\"2026-11-10T18:44:00Z\"}", "application/json", "demo");
    }

    private void endNotConfirmed() {
        var held = attempt(task("80010", "MODBAR-810", "Task whose run end Factory could not confirm"),
                TaskAttempt.State.NEEDS_INSPECTION, null, execution("0.6.0", ExecutionStatus.RUNNING, 3));
        var installation = installations.findById(1).orElseThrow();
        installation.setActiveAttemptId(held.getId());
        installations.save(installation);
        attempt(task("80023", "MODBAR-823", "Task whose run end is not confirmed and does not hold the queue"),
                TaskAttempt.State.NEEDS_INSPECTION, null, execution("0.6.0", ExecutionStatus.RUNNING, 3));
        // The run opened its pull request before Factory found a container it could not remove.
        var delivered = attempt(task("80034", "MODBAR-834", "Task whose run opened a pull request and whose end is not"
                + " confirmed"), TaskAttempt.State.NEEDS_INSPECTION, null,
                execution(FeedbackArtifacts.VERSION, ExecutionStatus.AWAITING_HITL, 9));
        artifacts.put(delivered.getExecutionId(), "dev_delivery.json", "{\"state\":\"DELIVERED\",\"pullRequestUrl\":\""
                + PULL + 34 + "\",\"pullRequestDraft\":true,\"draftReasons\":[\"1 acceptance criterion is not met.\"]}",
                "application/json", "demo");
    }

    private static String comment(String id, String author, String body, String path, int line, int pull) {
        return "{\"id\":\"" + id + "\",\"author\":\"" + author + "\",\"body\":\"" + body + "\",\"path\":\"" + path
                + "\",\"line\":" + line + ",\"outdated\":false,\"updatedAt\":\"\",\"url\":\"" + PULL + pull
                + "#discussion_" + id.substring(id.indexOf(':') + 1) + "\"}";
    }

    private Task task(String issueId, String key, String title) {
        return tasks.save(TaskFixture.task("MODBAR", issueId, key, title,
                "{\"key\": \"" + key + "\", \"status\": \"Open\"}", Task.Eligibility.ELIGIBLE, ELIGIBLE));
    }

    private TaskAttempt attempt(Task task, TaskAttempt.State state, String reason, PipelineExecution execution) {
        return attempt(task, 1, state, reason, execution);
    }

    private TaskAttempt attempt(Task task, int ordinal, TaskAttempt.State state, String reason,
                                PipelineExecution execution) {
        var kind = ordinal == 1 ? TaskAttempt.Kind.INITIAL : TaskAttempt.Kind.RETRY;
        var attempt = new TaskAttempt(task.getId(), ordinal, kind, "demo-" + task.getId() + "-" + ordinal, state);
        attempt.setResultReason(reason);
        if (state != TaskAttempt.State.RESERVED) {
            attempt.setStartedAt(Instant.now().minus(Duration.ofMinutes(12)));
            attempt.setDeadlineAt(Instant.now().plus(Duration.ofMinutes(18)));
        }
        if (execution != null) {
            attempt.setExecutionId(execution.getId());
            var settings = settingsByRun.get(execution.getId());
            attempt.setRunSettings(settings == null ? null : JSON.writeValueAsString(settings));
            // A run that reported usage reached the agent, so its attempt shows the time the agent worked.
            attempt.setPaidStartAt(settings == null ? null : attempt.getStartedAt());
        }
        return attempts.save(attempt);
    }

    /** A run that stands on the step with this index and whose coding step finished with its usage reported. */
    private PipelineExecution execution(String flowVersion, ExecutionStatus status, int step) {
        var execution = bareExecution(flowVersion, status, step);
        usage(execution, AuditEventType.STEP_COMPLETED, 1 + settingsByRun.size() % 4);
        return execution;
    }

    /**
     * The usage of one coding call of the run with the next model and effort of {@link #RUNS}, recorded as the coding
     * step records it: with its price, as a finished step or as live progress. {@code scale} multiplies the tokens.
     */
    private void usage(PipelineExecution execution, AuditEventType type, int scale) {
        var settings = settingsByRun.computeIfAbsent(execution.getId(), id -> RUNS.get(settingsByRun.size() % 3));
        var rate = rates.snapshot(settings.modelId(), null);
        var usage = new LinkedHashMap<String, Object>(TokenUsage.withFactoryTotals(Map.<String, Object>of("inputTokens",
                184_000L * scale, "cacheReadTokens", 920_000L * scale, "cacheWriteTokens", 0L,
                "outputTokens", 12_400L * scale)));
        usage.put("attemptId", UUID.randomUUID().toString());
        usage.put("model", rate.get("model"));
        usage.put("rate", rate);
        if (type == AuditEventType.RUNTIME_PROGRESS) {
            usage.put("activity", "coding_usage");
        } else {
            startedMinutesAgo(execution.getId(), "implement", Math.min(60, 6 * scale));
        }
        auditLog.record(execution.getId(), type, "implement", usage);
    }

    /**
     * The start of a step in the past, so a finished run shows the time it took and not "0 s", and a round that
     * works shows its own time. The audit log stamps every event with the current time, so this one row is
     * inserted directly.
     */
    private void startedMinutesAgo(UUID executionId, String stepId, int minutes) {
        jdbc.update("INSERT INTO audit_event (execution_id, event_type, step_id, actor, occurred_at)"
                + " VALUES (?, 'STEP_STARTED', ?, 'demo', now() - make_interval(mins => ?))",
                executionId, stepId, minutes);
    }

    /** A run that stands on the step with this index and reported no usage. */
    private PipelineExecution bareExecution(String flowVersion, ExecutionStatus status, int step) {
        var execution = executions.save(new PipelineExecution("dev-factory", flowVersion, "{}"));
        execution.setStatus(status);
        execution.setCurrentStepIndex(step);
        return executions.save(execution);
    }
}
