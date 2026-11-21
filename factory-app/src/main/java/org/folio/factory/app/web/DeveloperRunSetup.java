package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.app.tasks.persistence.TaskRoutingReason;
import org.folio.factory.app.web.DeveloperConfigurationView.Fact;
import org.folio.factory.app.web.DeveloperConfigurationView.Route;
import org.folio.factory.app.web.DeveloperConfigurationView.Routes;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.DevFactoryProperties.Repository;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Where runs go and the limits of every run, read from the repository catalog and the runtime settings. The
 * Configuration tab shows them; nothing here calls Jira, GitHub or Docker.
 */
@Component
@RequiredArgsConstructor
public class DeveloperRunSetup {

    private static final String CATALOG = TaskRoutingReason.CATALOG_FILE;

    private final DevFactoryProperties repositories;
    private final DevRuntimeProperties settings;
    private final GitHubProperties github;
    private final CodingRuntime coding;
    private final TaskExecutionProperties execution;

    boolean claudeCode() {
        return ClaudeCodeCodingRuntime.ID.equals(coding.id());
    }

    /** One row per repository in the catalog, and whether Factory has a token to open pull requests. */
    Routes routes() {
        var rows = repositories.repositories().entrySet().stream()
                .map(entry -> route(entry.getKey(), entry.getValue())).toList();
        var token = github.isConfigured()
                ? "FACTORY_CONNECTORS_GITHUB_TOKEN is set, so Factory can open pull requests."
                : "FACTORY_CONNECTORS_GITHUB_TOKEN is not set, so Factory cannot open pull requests. Set it in"
                        + " .env to a token that can push to the repositories above, then run docker compose up -d.";
        return new Routes(rows, token);
    }

    /** The time, turn and image limits of every run, each with the variable that changes it. */
    List<Fact> limits() {
        var limits = new ArrayList<Fact>();
        limits.add(new Fact("Time per step", UiFormat.duration(settings.timeoutSeconds()) + " each for the first"
                + " build, the coding and the checks (never past the time per run), and for every PR"
                + " feedback round.", "FACTORY_DEV_FACTORY_TIMEOUT_SECONDS"));
        limits.add(new Fact("Time per run", UiFormat.duration(execution.deadline().toSeconds())
                + " from Prepare task to the pull request; waiting in the queue or for a review does not count.",
                "FACTORY_TASKS_EXECUTION_DEADLINE"));
        if (claudeCode()) {
            limits.add(new Fact("Agent turns", settings.coding().claudeCode().maxTurns() + " per coding session,"
                    + " from 1 to 500. A session that reaches it stops, and its work is lost.",
                    "FACTORY_CLAUDE_CODE_MAX_TURNS"));
        }
        limits.add(new Fact("Coding agent", claudeCode() ? "Claude Code" : "Pi",
                "FACTORY_DEV_FACTORY_CODING_RUNTIME"));
        limits.add(new Fact("Coding image", coding.image(),
                claudeCode() ? "FACTORY_CLAUDE_CODE_IMAGE" : "FACTORY_PI_IMAGE"));
        return List.copyOf(limits);
    }

    private Route route(String key, Repository repository) {
        return new Route(projects(repository), repository.sourceRepo() + ", branch " + repository.baseBranch(),
                pullRequests(key, repository), checks(repository));
    }

    /** The Jira projects of a repository, with the components that route a task to it when it lists any. */
    private static String projects(Repository repository) {
        var projects = String.join(", ", repository.jiraProjects());
        return repository.jiraComponents().isEmpty() ? projects
                : projects + " (components " + String.join(", ", repository.jiraComponents()) + ")";
    }

    private static String pullRequests(String key, Repository repository) {
        var target = repository.delivery();
        return target == null || target.repository() == null
                ? "Not set: add delivery.repository to " + key + " in " + CATALOG + "."
                : target.repository();
    }

    private String checks(Repository repository) {
        var plan = repository.verificationPlan();
        var command = settings.plans().getOrDefault(plan, List.of());
        return command.isEmpty()
                ? "Plan " + plan + " is missing: add it under runtime.plans in " + CATALOG + "."
                : String.join(" ", command) + " in " + repository.buildImage();
    }
}
