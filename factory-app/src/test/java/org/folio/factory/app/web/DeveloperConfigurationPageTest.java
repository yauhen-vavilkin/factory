package org.folio.factory.app.web;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.developer.DeveloperPreflight.Check;
import org.folio.factory.app.developer.DeveloperPreflight.Status;
import org.folio.factory.app.developer.ModelChecks.ModelRow;
import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.presentation.SourceCard;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.folio.factory.app.web.DeveloperConfigurationView.Fact;
import org.folio.factory.app.web.DeveloperConfigurationView.ReadyModel;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.retention.RetentionProperties;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeveloperConfigurationPageTest {

    private static final ModelRow READY = new ModelRow("sonnet", true, true, "claude-sonnet-5-5",
            "anthropic-messages", "http://proxy:4001", "bearer-token", "low, high; default high",
            "default · proxy:4001 · effort low, high · no price",
            List.of(new Check("api", Status.PASS, "fine"), new Check("price", Status.WARN, "Add a price.")));
    private static final ModelRow BLOCKED = new ModelRow("example-large", false, false, "example-large",
            "anthropic-messages", "https://api.example.com", "bearer-token", "max; default max",
            "api.example.com · effort max · no price",
            List.of(new Check("secret", Status.FAIL, "Set FACTORY_EXAMPLE_API_KEY."),
                    new Check("price", Status.WARN, "Add a price.")));

    @Mock
    private DeveloperPreflight preflight;

    @Mock
    private TaskRows rows;

    @Mock
    private TaskInstallationRepository installations;

    @Mock
    private CodingRuntime coding;

    @BeforeEach
    void setUp() {
        when(coding.id()).thenReturn("claude-code");
        when(coding.image()).thenReturn("factory-dev-claude-code:test");
        var installation = TaskInstallations.seeded();
        when(installations.findById(1)).thenReturn(Optional.of(installation));
        when(rows.sourceCard(installation)).thenReturn(new SourceCard("Jane", "jira.example.org",
                "assignee = \"Jane\"", "https://jira.example.org/issues/?jql=x", null, null));
    }

    @Test
    void readyModelsKeepTheirWarningsAndBlockedModelsOnlyTheirFixes() {
        var view = view(true, false, "", List.of(), List.of(READY, BLOCKED));

        assertThat(view.models().ready()).singleElement().satisfies(model -> {
            assertThat(model.summary()).isEqualTo("default · proxy:4001 · effort low, high · no price");
            assertThat(model.warnings()).containsExactly("Add a price.");
        });
        assertThat(view.models().blocked()).singleElement().satisfies(model -> {
            assertThat(model.name()).isEqualTo("example-large");
            assertThat(model.fixes()).containsExactly("Set FACTORY_EXAMPLE_API_KEY.");
        });
        assertThat(view.start().title()).isEqualTo("Factory can start runs");
        assertThat(view.start().fixes()).isEmpty();
        assertThat(view.task().verdict()).isNull();
        assertThat(view.task().rows()).isEmpty();
    }

    @Test
    void modelsWhoseEndpointFactoryCannotReachAreGroupedByEndpointAndStillCountAsRunnable() {
        var unreachable = new Check("endpoint", Status.WARN, "Endpoint not reachable: Factory cannot open a"
                + " connection to http://proxy:4001.");
        var sonnet = new ModelRow("sonnet", true, true, "claude-sonnet-5-5", "anthropic-messages",
                "http://proxy:4001", "bearer-token", "high; default high", "default · proxy:4001",
                List.of(unreachable, new Check("price", Status.WARN, "Add a price.")));
        var haiku = new ModelRow("haiku", false, true, "claude-haiku-4-5", "anthropic-messages",
                "http://proxy:4001", "bearer-token", "low; default low", "proxy:4001", List.of(unreachable));

        var view = view(true, false, "", List.of(), List.of(sonnet, haiku, BLOCKED));

        assertThat(view.models().ready()).isEmpty();
        assertThat(view.models().unreachableCount()).isEqualTo(2);
        assertThat(view.models().unreachable()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.warning()).isEqualTo("Factory cannot open a connection to http://proxy:4001. Runs"
                    + " with these models fail if the coding container cannot reach it either. Start the service"
                    + " at that address, or fix base-url in config/developer-models.yaml.");
            // The endpoint warning is said once above the models; other warnings stay with their model.
            assertThat(endpoint.models()).extracting(ReadyModel::id, ReadyModel::warnings)
                    .containsExactly(tuple("sonnet", List.of("Add a price.")), tuple("haiku", List.of()));
        });
        assertThat(view.start().fixes()).doesNotContain("No model can run now. Models above says what each model"
                + " needs.");
    }

    @Test
    void factoryCannotStartRunsWithoutAModelDockerOrTaskRuns() {
        var docker = new Check("docker", Status.FAIL, "Start Docker.");
        var defaultModel = new Check("model", Status.FAIL, "The default model cannot run.");
        var view = view(false, false, "", List.of(docker, defaultModel), List.of(BLOCKED));

        assertThat(view.start().title()).isEqualTo("Factory cannot start runs");
        assertThat(view.start().fixes()).containsExactly("Start Docker.",
                "No model can run now. Models above says what each model needs.",
                "Runs from My tasks are off. Set FACTORY_TASKS_EXECUTION_ENABLED=true in .env, then run"
                        + " docker compose up -d.");
    }

    @Test
    void aBrokenDefaultModelDoesNotStopRunsWhileAnotherModelIsReady() {
        var defaultModel = new Check("model", Status.FAIL, "The default model cannot run.");
        var view = view(true, false, "MODSIDECAR-1",
                List.of(defaultModel, new Check("jira", Status.PASS, "Requested issue is readable.")),
                List.of(READY));

        assertThat(view.start().fixes()).isEmpty();
        assertThat(view.task().verdict()).startsWith("MODSIDECAR-1 can run.");
        assertThat(view.task().rows()).extracting(DeveloperConfigurationView.CheckRow::label)
                .containsExactly("Passed");
    }

    @Test
    void retentionStopsRunsAndTheTaskVerdictSaysSo() {
        var view = view(true, true, "MODSIDECAR-1",
                List.of(new Check("jira", Status.PASS, "Readable."),
                        new Check("inference", Status.NOT_RUN, "No model call made.")), List.of(READY));

        assertThat(view.start().fixes()).singleElement().asString().contains("FACTORY_RETENTION_ENABLED");
        assertThat(view.task().verdict()).isEqualTo("The checks of MODSIDECAR-1 pass, but Factory cannot start"
                + " runs yet: fix the problems under Factory cannot start runs first.");
        assertThat(view.task().rows()).extracting(DeveloperConfigurationView.CheckRow::text)
                .containsExactly("Readable.");
    }

    @Test
    void aFailedTaskCheckIsTheVerdict() {
        var view = view(true, false, "MODSIDECAR-1", List.of(
                new Check("mapping", Status.FAIL, "No repository matches."),
                new Check("repository-choice", Status.NOT_RUN, "Confirm one in review."),
                new Check("build-image:sidecar", Status.WARN, "Docker will pull it.")), List.of(READY));

        assertThat(view.task().verdict()).isEqualTo("MODSIDECAR-1 cannot run yet. Fix the failed checks below,"
                + " then check again.");
        assertThat(view.task().rows()).extracting(DeveloperConfigurationView.CheckRow::label,
                        DeveloperConfigurationView.CheckRow::chipClass)
                .containsExactly(tuple("Failed", "chip-danger"),
                        tuple("Not checked", "chip-neutral"),
                        tuple("Warning", "chip-warning"));
    }

    @Test
    void tasksComeFromNamesTheVariablesAndNeverTheirValues() {
        var view = view(true, false, "", List.of(), List.of(READY));

        assertThat(view.source().facts()).extracting(Fact::name, Fact::text, Fact::variable).containsExactly(
                tuple("Jira", "jira.example.org", "FACTORY_CONNECTORS_JIRA_BASE_URL"),
                tuple("Assignee", "Jane", "FACTORY_TASKS_ASSIGNEE"),
                tuple("Jira sign-in", "Set: Factory reads Jira as the account in FACTORY_CONNECTORS_JIRA_EMAIL"
                        + " and FACTORY_CONNECTORS_JIRA_API_TOKEN, so it sees the issues that account can see.", null));
        assertThat(view.source().searchUrl()).isEqualTo("https://jira.example.org/issues/?jql=x");
        assertThat(view.toString()).doesNotContain("jira-secret-token", "github-secret-token");
    }

    @Test
    void piRunsListNoModelsAndSayWhereTheirModelComesFrom() {
        when(coding.id()).thenReturn("pi");

        var view = view(true, false, "", List.of(), List.of());

        assertThat(view.models().listed()).isFalse();
        assertThat(view.models().note()).contains("FACTORY_PI_MODEL");
        assertThat(view.start().fixes()).isEmpty();
        assertThat(view.limits()).extracting(Fact::name).doesNotContain("Agent turns");
        assertThat(view.limits()).extracting(Fact::variable).contains("FACTORY_PI_IMAGE");
    }

    @Test
    void whereRunsGoShowsComponentsAndTheFixOfAMissingPlanOrDelivery() {
        var routes = runSetup(false).routes();

        assertThat(routes.rows()).extracting(DeveloperConfigurationView.Route::projects,
                        DeveloperConfigurationView.Route::source, DeveloperConfigurationView.Route::pullRequests,
                        DeveloperConfigurationView.Route::checks)
                .containsExactly(
                        tuple("MGRAPPS (components api)", "folio-org/mgr-applications,"
                                        + " branch master", "Not set: add delivery.repository to apps in"
                                        + " config/developer-repositories.yaml.",
                                "Plan java21-missing is missing: add it under runtime.plans in"
                                        + " config/developer-repositories.yaml."),
                        tuple("MODSIDECAR", "folio-org/folio-module-sidecar, branch"
                                + " master", "me/folio-module-sidecar", "mvn -B -ntp test in maven:3.9"));
        assertThat(routes.token()).startsWith("FACTORY_CONNECTORS_GITHUB_TOKEN is not set");
        assertThat(runSetup(true).routes().token()).doesNotContain("github-secret-token");
    }

    @Test
    void limitsNameEveryVariable() {
        assertThat(runSetup(true).limits()).extracting(Fact::name, Fact::variable).containsExactly(
                tuple("Time per step", "FACTORY_DEV_FACTORY_TIMEOUT_SECONDS"),
                tuple("Time per run", "FACTORY_TASKS_EXECUTION_DEADLINE"),
                tuple("Agent turns", "FACTORY_CLAUDE_CODE_MAX_TURNS"),
                tuple("Coding agent", "FACTORY_DEV_FACTORY_CODING_RUNTIME"),
                tuple("Coding image", "FACTORY_CLAUDE_CODE_IMAGE"));
        assertThat(runSetup(true).limits()).extracting(Fact::text).startsWith(
                "1 h each for the first build, the coding and the checks (never past the time per run), and"
                        + " for every PR feedback round.",
                "1 h from Prepare task to the pull request; waiting in the queue or for a review does not count.",
                "400 per coding session, from 1 to 500. A session that reaches it stops, and its work is lost.");
    }

    private DeveloperConfigurationView view(boolean runsOn, boolean retentionOn, String issueKey, List<Check> checks,
                                            List<ModelRow> models) {
        when(preflight.check(issueKey))
                .thenReturn(new DeveloperPreflight.Report(false, issueKey, Map.of(), List.of(), checks, models));
        return new DeveloperConfigurationPage(preflight, runSetup(true), rows, installations,
                new JiraProperties("https://jira.example.org", "jane@example.org", "jira-secret-token"),
                new TaskExecutionProperties(Duration.ofMinutes(60), runsOn),
                new RetentionProperties(retentionOn, null, null)).view(issueKey);
    }

    private DeveloperRunSetup runSetup(boolean token) {
        var repositories = new TreeMap<String, DevFactoryProperties.Repository>();
        repositories.put("sidecar", new DevFactoryProperties.Repository("folio-org/folio-module-sidecar", "master",
                "maven:3.9", "java21-unit", List.of("MODSIDECAR"), List.of(),
                new DeliveryTarget("me/folio-module-sidecar", null)));
        repositories.put("apps", new DevFactoryProperties.Repository("folio-org/mgr-applications", "master",
                "maven:3.9", "java21-missing", List.of("MGRAPPS"), List.of("api"), null));
        var settings = new DevRuntimeProperties(Map.of("java21-unit", List.of("mvn", "-B", "-ntp", "test")), null, 0,
                null, null);
        return new DeveloperRunSetup(new DevFactoryProperties(null, repositories), settings,
                new GitHubProperties(null, token ? "github-secret-token" : null), coding,
                new TaskExecutionProperties(Duration.ofMinutes(60), true));
    }
}
