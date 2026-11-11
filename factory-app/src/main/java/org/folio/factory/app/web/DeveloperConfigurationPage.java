package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.developer.DeveloperPreflight.Check;
import org.folio.factory.app.developer.DeveloperPreflight.Report;
import org.folio.factory.app.developer.DeveloperPreflight.Status;
import org.folio.factory.app.developer.ModelChecks.ModelRow;
import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.presentation.SourceCard;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.web.DeveloperConfigurationView.BlockedModel;
import org.folio.factory.app.web.DeveloperConfigurationView.CheckRow;
import org.folio.factory.app.web.DeveloperConfigurationView.Fact;
import org.folio.factory.app.web.DeveloperConfigurationView.Models;
import org.folio.factory.app.web.DeveloperConfigurationView.ReadyModel;
import org.folio.factory.app.web.DeveloperConfigurationView.Source;
import org.folio.factory.app.web.DeveloperConfigurationView.Start;
import org.folio.factory.app.web.DeveloperConfigurationView.TaskCheck;
import org.folio.factory.app.web.DeveloperConfigurationView.UnreachableEndpoint;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Builds the Configuration tab from one preflight report and the settings. The preflight makes the only
 * network checks (Docker, the model endpoints, and Jira and GitHub for an entered task).
 */
@Component
@RequiredArgsConstructor
public class DeveloperConfigurationPage {

    /**
     * Factory checks that stop every run. The model check covers only the default model, which a run form does
     * not need, so Models shows it instead.
     */
    private static final Set<String> START_CHECKS = Set.of("runtime", "docker", "coding-image");
    private static final String MODELS_NOTE = "From " + ModelCatalog.FILE + ", or from the file"
            + " FACTORY_DEV_FACTORY_MODELS_FILE points to. The run forms offer the models under Ready to run and"
            + " Endpoint not reachable. After a change to the file, run docker compose restart factory.";
    private static final String ENDPOINT_CHECK = "endpoint";
    private static final String PI_NOTE = "Runs use Pi, which takes its model from FACTORY_PI_MODEL in .env; "
            + ModelCatalog.FILE + " gives Pi only prices.";

    private final DeveloperPreflight preflight;
    private final DeveloperRunSetup runSetup;
    private final TaskRows rows;
    private final TaskInstallationRepository installations;
    private final JiraProperties jira;
    private final TaskExecutionProperties execution;
    private final RetentionProperties retention;

    public DeveloperConfigurationView view(String issueKey) {
        var report = preflight.check(issueKey);
        var models = models(report.models());
        var start = start(report, models);
        return new DeveloperConfigurationView(models, runSetup.routes(), runSetup.limits(), source(), start,
                task(report, start));
    }

    private Models models(List<ModelRow> models) {
        if (!runSetup.claudeCode()) {
            return new Models(false, List.of(), List.of(), List.of(), PI_NOTE);
        }
        var runnable = models.stream().filter(ModelRow::ready).toList();
        var ready = runnable.stream().filter(row -> !unreachable(row)).map(DeveloperConfigurationPage::readyModel)
                .toList();
        var blocked = models.stream().filter(row -> !row.ready())
                .map(row -> new BlockedModel(row.defaultModel() ? row.id() + " · default" : row.id(),
                        messages(row.checks(), Status.FAIL)))
                .toList();
        return new Models(true, ready, unreachableEndpoints(runnable), blocked, MODELS_NOTE);
    }

    private static boolean unreachable(ModelRow row) {
        return row.checks().stream()
                .anyMatch(check -> ENDPOINT_CHECK.equals(check.name()) && check.status() == Status.WARN);
    }

    /** The summary and the warnings but the endpoint one, which its group says once. */
    private static ReadyModel readyModel(ModelRow row) {
        var warnings = row.checks().stream()
                .filter(check -> check.status() == Status.WARN && !ENDPOINT_CHECK.equals(check.name()))
                .map(Check::message).toList();
        return new ReadyModel(row.id(), row.summary(), warnings);
    }

    private static List<UnreachableEndpoint> unreachableEndpoints(List<ModelRow> runnable) {
        var byOrigin = new LinkedHashMap<String, List<ReadyModel>>();
        runnable.stream().filter(DeveloperConfigurationPage::unreachable).forEach(row -> byOrigin
                .computeIfAbsent(row.endpointOrigin(), origin -> new ArrayList<>()).add(readyModel(row)));
        return byOrigin.entrySet().stream()
                .map(entry -> new UnreachableEndpoint(entry.getKey(),
                        unreachableWarning(entry.getKey(), entry.getValue().size()), List.copyOf(entry.getValue())))
                .toList();
    }

    private static String unreachableWarning(String origin, int models) {
        return "Factory cannot open a connection to " + origin + ". Runs with " + (models == 1 ? "this model" : "these"
                + " models") + " fail if the coding container cannot reach it either. Start the service at that"
                + " address, or fix base-url in " + ModelCatalog.FILE + ".";
    }

    private Start start(Report report, Models models) {
        var fixes = new ArrayList<>(report.checks().stream()
                .filter(check -> START_CHECKS.contains(check.name()) && check.status() == Status.FAIL)
                .map(Check::message).toList());
        if (models.listed() && !models.runnable()) {
            fixes.add("No model can run now. Models above says what each model needs.");
        }
        if (!execution.enabled()) {
            fixes.add("Runs from My tasks are off. Set FACTORY_TASKS_EXECUTION_ENABLED=true in .env, then run"
                    + " docker compose up -d.");
        } else if (retention.enabled()) {
            fixes.add("Factory starts no runs while FACTORY_RETENTION_ENABLED is true, because retention would"
                    + " delete the history and costs of attempts. Set it to false in .env, then run"
                    + " docker compose up -d.");
        }
        return fixes.isEmpty()
                ? new Start("Factory can start runs", "Docker is running, the coding image is on this machine,"
                        + " and runs from My tasks are on.", List.of())
                : new Start("Factory cannot start runs", null, List.copyOf(fixes));
    }

    private Source source() {
        return installations.findById(1).map(rows::sourceCard).map(this::source)
                .orElseGet(() -> new Source(List.of(new Fact("My tasks", "Not set up: the database has no My"
                        + " tasks row. Restart Factory so that its migration V12 creates it.", null)), null, null));
    }

    private Source source(SourceCard card) {
        var host = card.jiraHost() == null
                ? new Fact("Jira", "Not set: set FACTORY_CONNECTORS_JIRA_BASE_URL in .env.", null)
                : new Fact("Jira", card.jiraHost(), "FACTORY_CONNECTORS_JIRA_BASE_URL");
        var assignee = card.assignee() == null
                ? new Fact("Assignee", "Not set: set FACTORY_TASKS_ASSIGNEE in .env to your name as Jira shows it.",
                        null)
                : new Fact("Assignee", card.assignee(), "FACTORY_TASKS_ASSIGNEE");
        return new Source(List.of(host, assignee, signIn()), card.query(), card.searchUrl());
    }

    private Fact signIn() {
        return jira.isConfigured()
                ? new Fact("Jira sign-in", "Set: Factory reads Jira as the account in FACTORY_CONNECTORS_JIRA_EMAIL"
                        + " and FACTORY_CONNECTORS_JIRA_API_TOKEN, so it sees the issues that account can see.", null)
                : new Fact("Jira sign-in", "Not set: Factory reads Jira without signing in, so it sees only public"
                        + " issues. To read other issues, set FACTORY_CONNECTORS_JIRA_EMAIL and"
                        + " FACTORY_CONNECTORS_JIRA_API_TOKEN in .env.", null);
    }

    /** The checks of the entered task; Factory's own checks are under Factory can start runs. */
    private static TaskCheck task(Report report, Start start) {
        if (report.issueKey().isEmpty()) {
            return new TaskCheck("", null, List.of());
        }
        var checks = report.checks().stream()
                .filter(check -> !DeveloperPreflight.FACTORY_CHECKS.contains(check.name())).toList();
        return new TaskCheck(report.issueKey(), verdict(report.issueKey(), checks, start),
                checks.stream().map(DeveloperConfigurationPage::checkRow).toList());
    }

    /** A run form sends a ready model, so a broken default model alone does not stop the task. */
    private static String verdict(String issueKey, List<Check> checks, Start start) {
        if (checks.stream().anyMatch(check -> check.status() == Status.FAIL)) {
            return issueKey + " cannot run yet. Fix the failed checks below, then check again.";
        }
        if (!start.fixes().isEmpty()) {
            return "The checks of " + issueKey + " pass, but Factory cannot start runs yet: fix the problems"
                    + " under Factory cannot start runs first.";
        }
        return issueKey + " can run. No model was called, so a problem at the model provider can still stop"
                + " the run.";
    }

    private static CheckRow checkRow(Check check) {
        return switch (check.status()) {
            case PASS -> new CheckRow("Passed", "chip-success", check.message());
            case FAIL -> new CheckRow("Failed", "chip-danger", check.message());
            case WARN -> new CheckRow("Warning", "chip-warning", check.message());
            case NOT_RUN -> new CheckRow("Not checked", "chip-neutral", check.message());
        };
    }

    private static List<String> messages(List<Check> checks, Status status) {
        return checks.stream().filter(check -> check.status() == status).map(Check::message).toList();
    }
}
