package org.folio.factory.flowa.worker;

import org.folio.factory.agents.artifact.Frontmatter;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.ConnectorNotConfiguredException;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.testrail.TestRailConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.flowa.FlowAProperties;
import org.folio.factory.flowa.artifact.ScriptBundleCodec;
import org.folio.factory.flowa.model.ScriptBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Final side-effect step: TestRail sync, repository commit + PR, Jira update.
 * Every action degrades gracefully when its connector is not configured — the
 * skip is recorded in the audit log and the sync report, and the flow completes.
 */
public class TestFactoryFinalizerWorker implements AgentWorker {

    public static final String ID = "test-factory-finalizer";

    private static final Logger log = LoggerFactory.getLogger(TestFactoryFinalizerWorker.class);

    private final JiraConnector jira;
    private final GitHubConnector gitHub;
    private final TestRailConnector testRail;
    private final FrontmatterCodec frontmatterCodec;
    private final ScriptBundleCodec bundleCodec;
    private final FlowAProperties properties;
    private final AuditLog auditLog;

    public TestFactoryFinalizerWorker(JiraConnector jira, GitHubConnector gitHub,
                                      TestRailConnector testRail, FrontmatterCodec frontmatterCodec,
                                      ScriptBundleCodec bundleCodec, FlowAProperties properties,
                                      AuditLog auditLog) {
        this.jira = jira;
        this.gitHub = gitHub;
        this.testRail = testRail;
        this.frontmatterCodec = frontmatterCodec;
        this.bundleCodec = bundleCodec;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Frontmatter plan = frontmatterCodec.parse(context.requireInput("test_plan.md").content());
        Frontmatter results = frontmatterCodec.parse(context.requireInput("test_results.md").content());
        ScriptBundle bundle = bundleCodec.parse(context.requireInput("test_scripts.md").content());
        String issueKey = plan.metadata().path("issue_key").asString("");
        if (issueKey.isBlank() && context.triggerPayload() != null) {
            issueKey = context.triggerPayload().path("issueKey").asString("");
        }

        List<Map<String, String>> actions = new ArrayList<>();
        String prUrl = syncGitHub(context, actions, bundle, issueKey);
        syncTestRail(context, actions, plan, results, issueKey);
        syncJira(context, actions, results, issueKey, prUrl);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", issueKey);
        metadata.put("actions", actions);
        StringBuilder body = new StringBuilder("# Sync Report — ").append(issueKey).append("\n\n");
        actions.forEach(action -> body.append("- **").append(action.get("connector")).append("** ")
                .append(action.get("action")).append(": ").append(action.get("status"))
                .append(action.get("detail").isBlank() ? "" : " — " + action.get("detail")).append("\n"));
        return AgentResult.of("sync_report.md", frontmatterCodec.render(metadata, body.toString()));
    }

    private String syncGitHub(AgentContext context, List<Map<String, String>> actions,
                              ScriptBundle bundle, String issueKey) {
        if (properties.targetRepo() == null || properties.targetRepo().isBlank()) {
            record(context, actions, "github", "commit scripts", "skipped",
                    "no target repository configured (FACTORY_FLOWA_TARGET_REPO)");
            return null;
        }
        String branch = "test-factory/" + issueKey;
        try {
            gitHub.createBranch(properties.targetRepo(), properties.baseBranch(), branch);
            Map<String, String> files = new HashMap<>();
            bundle.files().forEach(f -> files.put(f.path(), f.content()));
            gitHub.commitFiles(properties.targetRepo(), branch, files,
                    issueKey + ": generated test scripts (AI Test Factory)");
            String prUrl = gitHub.createPullRequest(properties.targetRepo(), branch, properties.baseBranch(),
                    issueKey + ": AI-generated test scripts",
                    "Generated by the AI SDLC Factory Test Factory flow for " + issueKey
                            + ".\n\nLabels: ai-generated");
            record(context, actions, "github", "branch + commit + PR", "done", prUrl);
            return prUrl;
        } catch (ConnectorNotConfiguredException e) {
            record(context, actions, "github", "commit scripts", "skipped", e.getMessage());
            return null;
        }
    }

    private void syncTestRail(AgentContext context, List<Map<String, String>> actions,
                              Frontmatter plan, Frontmatter results, String issueKey) {
        if (properties.testrailSectionId() == null) {
            record(context, actions, "testrail", "sync cases", "skipped",
                    "no TestRail section configured (FACTORY_FLOWA_TESTRAIL_SECTION_ID)");
            return;
        }
        try {
            Map<String, Long> testRailCaseIds = new LinkedHashMap<>();
            for (JsonNode testCase : plan.metadata().path("cases")) {
                long caseId = testRail.addCase(properties.testrailSectionId(),
                        "[" + issueKey + "] " + testCase.path("title").asString(""),
                        String.join("\n", toStrings(testCase.path("steps"))),
                        testCase.path("expected").asString(""));
                testRailCaseIds.put(testCase.path("id").asString(""), caseId);
            }
            long runId = testRail.addRun(issueKey + " — AI Test Factory",
                    new ArrayList<>(testRailCaseIds.values()));
            boolean executed = "EXECUTED".equals(results.metadata().path("mode").asString(""));
            if (executed) {
                Map<Long, Boolean> outcome = new LinkedHashMap<>();
                for (JsonNode caseResult : results.metadata().path("case_results")) {
                    Long testRailId = testRailCaseIds.get(caseResult.path("case_id").asString(""));
                    if (testRailId != null) {
                        outcome.put(testRailId, "PASSED".equals(caseResult.path("status").asString("")));
                    }
                }
                testRail.addResults(runId, outcome, "Automated by AI Test Factory");
            }
            record(context, actions, "testrail", "cases + run" + (executed ? " + results" : ""),
                    "done", testRailCaseIds.size() + " case(s), run " + runId);
        } catch (ConnectorNotConfiguredException e) {
            record(context, actions, "testrail", "sync cases", "skipped", e.getMessage());
        }
    }

    private void syncJira(AgentContext context, List<Map<String, String>> actions,
                          Frontmatter results, String issueKey, String prUrl) {
        String mode = results.metadata().path("mode").asString("");
        try {
            jira.addComment(issueKey, "AI Test Factory completed for " + issueKey
                    + ".\nResults mode: " + mode
                    + (prUrl == null ? "" : "\nTest scripts PR: " + prUrl));
            record(context, actions, "jira", "comment", "done", "");
        } catch (ConnectorNotConfiguredException e) {
            record(context, actions, "jira", "comment", "skipped", e.getMessage());
            return;
        }
        if (properties.jiraTransition() != null && !properties.jiraTransition().isBlank()) {
            try {
                jira.transitionIssue(issueKey, properties.jiraTransition());
                record(context, actions, "jira", "transition '" + properties.jiraTransition() + "'", "done", "");
            } catch (ConnectorNotConfiguredException | IllegalArgumentException e) {
                record(context, actions, "jira", "transition '" + properties.jiraTransition() + "'",
                        "skipped", e.getMessage());
            }
        }
    }

    private List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString("")));
        return values;
    }

    private void record(AgentContext context, List<Map<String, String>> actions,
                        String connector, String action, String status, String detail) {
        actions.add(Map.of("connector", connector, "action", action,
                "status", status, "detail", detail == null ? "" : detail));
        AuditEventType eventType = "skipped".equals(status)
                ? AuditEventType.CONNECTOR_SKIPPED : AuditEventType.CONNECTOR_ACTION;
        auditLog.record(context.executionId(), eventType, context.stepId(),
                Map.of("connector", connector, "action", action, "detail", detail == null ? "" : detail));
        if ("skipped".equals(status)) {
            log.info("Connector {} skipped for execution {}: {}", connector, context.executionId(), detail);
        }
    }
}
