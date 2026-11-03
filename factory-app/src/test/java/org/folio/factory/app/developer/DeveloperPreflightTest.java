package org.folio.factory.app.developer;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.connectors.jira.JiraProperties;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.delivery.DeliveryTarget;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.CodingRequest;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.folio.factory.devfactory.runtime.ModelEntry;
import org.folio.factory.devfactory.runtime.ModelPrice;
import org.folio.factory.devfactory.worker.IntakeWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.factory.app.developer.DeveloperPreflight.Status.FAIL;
import static org.folio.factory.app.developer.DeveloperPreflight.Status.NOT_RUN;
import static org.folio.factory.app.developer.DeveloperPreflight.Status.PASS;
import static org.folio.factory.app.developer.DeveloperPreflight.Status.WARN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskGuard;
import java.time.Clock;

class DeveloperPreflightTest {

    private static final String SHA = "a".repeat(40);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ModelPrice PRICE = new ModelPrice("USD", LocalDate.of(2026, 10, 22),
            URI.create("https://pricing.fixture.invalid"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null,
            BigDecimal.ONE);
    private static final ModelEntry GLM = new ModelEntry("glm-5.3", "anthropic-messages",
            "https://api.z.ai/api/anthropic", "bearer-token", "FACTORY_ZAI_API_KEY", "glm-5.3",
            List.of("low", "high", "max"), "max", null, null);

    private final CodingRuntime coding = mock(CodingRuntime.class);
    private final JiraConnector jira = mock(JiraConnector.class);
    private final DeveloperPreflightProbe probe = mock(DeveloperPreflightProbe.class);
    private final JiraProperties jiraProperties = new JiraProperties(
            "https://jira.fixture.invalid", "fixture@example.invalid", "jira-fixture-secret");
    private final GitHubProperties github = new GitHubProperties("", "github-fixture-secret");
    private DevRuntimeProperties settings;
    private DevFactoryProperties repositories;
    /** The default model; the other model of the file is {@link #GLM}. */
    private ModelEntry defaultModel;
    private Map<String, String> secrets;

    @BeforeEach
    void configuredFixture() {
        var runtime = new DevRuntimeProperties.Coding("claude-code", null,
                new DevRuntimeProperties.ClaudeCode("coding:image", 40));
        settings = new DevRuntimeProperties(Map.of("unit", List.of("mvn", "-B", "test")), runtime, 1800, null, null);
        repositories = catalog(Map.of("fixture", repository("source/module", true)));
        defaultModel = model("http://gateway.fixture.invalid:4001", PRICE);
        secrets = new HashMap<>(Map.of("CODING_SECRET", "coding-fixture-secret"));
        when(coding.id()).thenReturn("claude-code");
        when(coding.image()).thenReturn("coding:image");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "claude-code", "image", "coding:image", "model", "exact-model"));
        when(probe.gatewayReachable(any())).thenReturn(true);
        when(probe.dockerAvailable()).thenReturn(true);
        when(probe.imageAvailable(anyString())).thenReturn(true);
        when(probe.sourceBase(anyString(), anyString(), anyString())).thenReturn(SHA);
        when(jira.getIssue("TASK-1")).thenReturn(issue("TASK-1"));
    }

    @AfterEach
    void neverInvokesCoding() {
        verify(coding, never()).code(any(), any(), anyInt(), any(), any());
    }

    @Test
    void readyTaskChecksSourceAndDestinationWithoutClaimingInferenceCoverage() {
        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "runtime").status()).isEqualTo(PASS);
        assertThat(check(report, "delivery:fixture").status()).isEqualTo(PASS);
        assertThat(check(report, "inference").status()).isEqualTo(NOT_RUN);
        assertThat(report.runtime()).containsEntry("model", "exact-model");
        assertThat(report.repositories()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("source", "source/module").containsEntry("baseBranch", "campaign/base")
                .containsEntry("delivery", "fork/module"));
        verify(probe).requireDeliveryAccess(new DeliveryTarget("fork/module", "campaign/base"), SHA);
        assertThatCode(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidSelectedModelBlocksAdmissionWithoutProbingItsGatewayOrExposingCredential() {
        doThrow(new IllegalStateException("invalid coding-fixture-secret")).when(coding).requireConfigured(any());

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "runtime").status()).isEqualTo(FAIL);
        // Only the overview of every model probes endpoints; admission never probes this gateway.
        assertThat(report.checks()).noneMatch(check -> check.name().equals("gateway"));
        assertSafe(report);
        org.mockito.Mockito.clearInvocations(probe);
        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("preflight failed")
                .isNotInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageNotContaining("coding-fixture-secret");
        verify(probe, never()).gatewayReachable(any());
    }

    @Test
    void aTaskRunIsCheckedWithItsOwnModelSoABrokenDefaultDoesNotBlockIt() throws Exception {
        secrets = Map.of("FACTORY_ZAI_API_KEY", "zai-fixture-secret");
        var service = serviceWithClaudeCode();
        var payload = JSON.readTree("{\"issueKey\":\"TASK-1\"}");

        assertThatCode(() -> DeveloperPreflight.forRun(new CodingRequest.RunSettings("glm-5.3", "max", null),
                () -> {
                    service.requireReady("dev-factory", payload);
                    return null;
                })).doesNotThrowAnyException();
        verify(probe).gatewayReachable(URI.create("https://api.z.ai/api/anthropic"));
        // A run that uses the broken default, or a model that is gone, stops alone with a plain reason.
        assertThatThrownBy(() -> service.requireReady("dev-factory", payload))
                .isInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageContaining("set CODING_SECRET in .env");
        assertThatThrownBy(() -> DeveloperPreflight.forRun(new CodingRequest.RunSettings("gone", null, null),
                () -> {
                    service.requireReady("dev-factory", payload);
                    return null;
                }))
                .isInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageContaining("model gone").hasMessageContaining("config/developer-models.yaml");
    }

    @Test
    void theConfigurationPageListsEveryModelWithItsOwnChecks() {
        var report = serviceWithClaudeCode().check(null);

        assertThat(report.models()).extracting(ModelChecks.ModelRow::id).containsExactly("fixture", "glm-5.3");
        var fixture = report.models().getFirst();
        assertThat(fixture.defaultModel()).isTrue();
        assertThat(fixture.ready()).isTrue();
        assertThat(fixture.model()).isEqualTo("exact-model");
        assertThat(fixture.endpointOrigin()).isEqualTo("http://gateway.fixture.invalid:4001");
        assertThat(checks(fixture)).containsEntry("api", PASS).containsEntry("efforts", PASS)
                .containsEntry("secret", PASS).containsEntry("endpoint", PASS).containsEntry("price", PASS);
        var glm = report.models().get(1);
        assertThat(glm.defaultModel()).isFalse();
        assertThat(glm.ready()).isFalse();
        assertThat(glm.efforts()).isEqualTo("low, high, max; default max");
        assertThat(glm.endpointOrigin()).isEqualTo("https://api.z.ai");
        assertThat(checks(glm)).containsEntry("secret", FAIL).containsEntry("endpoint", PASS)
                .containsEntry("price", WARN).containsEntry("efforts", PASS);
        assertThat(JSON.writeValueAsString(report)).doesNotContain("coding-fixture-secret");
    }

    private static Map<String, DeveloperPreflight.Status> checks(ModelChecks.ModelRow row) {
        var checks = new java.util.LinkedHashMap<String, DeveloperPreflight.Status>();
        row.checks().forEach(check -> checks.put(check.name(), check.status()));
        return checks;
    }

    /** The preflight with the real Claude Code runtime over the fixture models, not the mocked runtime. */
    private DeveloperPreflight serviceWithClaudeCode() {
        return service(new ClaudeCodeCodingRuntime(settings.coding().claudeCode(), models()), github);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-url", "http://coding-fixture-secret@gateway.fixture.invalid:4001",
            "http://gateway.fixture.invalid:4001?token=coding-fixture-secret"})
    void invalidPiEndpointProducesOneRuntimeFailureWithoutAContradictoryPass(String baseUrl) {
        usePiProfile(baseUrl);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(report.checks()).filteredOn(check -> check.name().equals("runtime"))
                .singleElement().satisfies(check -> assertThat(check.status()).isEqualTo(FAIL));
        assertThat(report.runtime()).containsEntry("endpointOrigin", "unset or invalid");
        verify(probe, never()).gatewayReachable(any());
        assertSafe(report);
    }

    @Test
    void builtInPiEndpointDoesNotNeedAnExplicitGatewayProbe() {
        usePiProfile(null);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "runtime").status()).isEqualTo(PASS);
        assertThat(check(report, "gateway").status()).isEqualTo(NOT_RUN);
        verify(probe, never()).gatewayReachable(any());
    }

    @Test
    void pricingUsesTheConfiguredModelInsteadOfTheDisplayIdentity() {
        when(coding.identity(any())).thenReturn(Map.of("runtime", "claude-code", "model", "display-only-model"));

        var report = service().check("TASK-1");

        assertThat(check(report, "pricing").status()).isEqualTo(PASS);
    }

    @Test
    void dockerFailureBlocksAdmissionAndSkipsTheCodingImageProbe() {
        when(probe.dockerAvailable()).thenReturn(false);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "docker").status()).isEqualTo(FAIL);
        verify(probe, never()).imageAvailable("coding:image");
    }

    @Test
    void missingCodingImageBlocksAdmission() {
        when(probe.imageAvailable("coding:image")).thenReturn(false);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "coding-image").status()).isEqualTo(FAIL);
    }

    @Test
    void buildImageCanBePulledLaterWithoutBlockingAdmission() {
        when(probe.imageAvailable("build:image")).thenReturn(false);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "build-image:fixture").status()).isEqualTo(WARN);
    }

    @Test
    void missingRateCardWarnsWithoutBlockingOtherwiseReadyTask() {
        defaultModel = model("http://gateway.fixture.invalid:4001", null);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "pricing").status()).isEqualTo(WARN);
    }

    @Test
    void controlPlaneCannotReachCodingEndpointButDoesNotVetoTheCodingNetwork() {
        defaultModel = model("http://host.docker.internal:4001", PRICE);
        when(probe.gatewayReachable(any())).thenReturn(false);

        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "gateway").status()).isEqualTo(WARN);
        assertThat(check(report, "inference").status()).isEqualTo(NOT_RUN);
        assertThat(report.runtime()).containsEntry("endpointOrigin", "http://host.docker.internal:4001");
    }

    @Test
    void configurationOnlyReportDoesNotReadJiraOrSourceOrClaimTaskReadiness() {
        var report = service().check(null);

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "task").status()).isEqualTo(NOT_RUN);
        verifyNoInteractions(jira);
        verify(probe, never()).sourceBase(anyString(), anyString(), anyString());
        verify(probe, never()).requireDeliveryAccess(any(), anyString());
        assertSafe(report);
    }

    @Test
    void unrelatedFlowBypassesDeveloperChecks() {
        service().requireReady("test-factory", JSON.readTree("{}"));

        verifyNoInteractions(coding, jira, probe);
    }

    @Test
    void invalidIssueDoesNotReachJira() {
        var report = service().check("TASK-0");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "task").status()).isEqualTo(FAIL);
        verifyNoInteractions(jira);
    }

    @Test
    void aDifferentJiraIssueCannotPassTaskAdmission() {
        when(jira.getIssue("TASK-1")).thenReturn(issue("TASK-2"));

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "issue").status()).isEqualTo(FAIL);
        verify(probe, never()).sourceBase(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageContaining("Jira did not return the requested issue.");
    }

    @Test
    void anIssueJiraNoLongerHasCannotRunWhileFactoryItselfIsReady() {
        when(jira.getIssue("TASK-1")).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, ("{\"errorMessages\":[\"Issue does not exist or you do not have permission to see"
                        + " it.\"],\"errors\":{}}").getBytes(), null));

        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOfSatisfying(DeveloperPreflight.TaskNotRunnableException.class, e ->
                        assertThat(e.reason()).isEqualTo("Jira did not return the requested issue."));
    }

    @Test
    void anIssueNoRepositoryMatchesCannotRun() {
        when(jira.getIssue("OTHER-1")).thenReturn(issue("OTHER-1"));

        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"OTHER-1\"}")))
                .isInstanceOfSatisfying(DeveloperPreflight.TaskNotRunnableException.class, e ->
                        assertThat(e.reason()).startsWith("No configured repository matches"));
    }

    @Test
    void aFactoryFailureKeepsTheTaskWaitingEvenWhenTheTaskFailsToo() {
        when(probe.dockerAvailable()).thenReturn(false);
        when(jira.getIssue("TASK-1")).thenReturn(issue("TASK-2"));

        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageContaining("Docker daemon is unavailable.");
    }

    @Test
    void aNotFoundPageThatIsNotJiraKeepsTheTaskWaiting() {
        when(jira.getIssue("TASK-1")).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, "<html><body>404 Not Found</body></html>".getBytes(), null));

        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(DeveloperPreflight.TaskNotRunnableException.class)
                .hasMessageContaining("Cannot read the requested Jira issue");
    }

    @Test
    void anUnreadableJiraKeepsTheTaskWaiting() {
        when(jira.getIssue("TASK-1")).thenThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{\"issueKey\":\"TASK-1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(DeveloperPreflight.TaskNotRunnableException.class);
    }

    @Test
    void missingIssueIsRejectedBeforeProbesWithAnActionableMessage() {
        assertThatThrownBy(() -> service().requireReady("dev-factory", JSON.readTree("{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid Jira issueKey is required");
        verifyNoInteractions(jira, probe);
    }

    @Test
    void incompleteDestinationCanStillBeInspectedSafely() {
        var repository = new DevFactoryProperties.Repository("source/module", "campaign/base", "build:image", "unit",
                List.of("TASK"), List.of(), new DeliveryTarget(null, null));
        repositories = catalog(Map.of("fixture", repository));

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(report.repositories()).singleElement().satisfies(row -> assertThat(row).containsEntry("delivery", ""));
        assertThat(check(report, "delivery:fixture").status()).isEqualTo(FAIL);
        assertSafe(report);
    }

    @Test
    void unsupportedEnterpriseApiIsBlockedBeforeRepositoryAccess() {
        var enterprise = new GitHubProperties("https://github.enterprise.invalid/api/v3", "github-fixture-secret");
        var service = service(coding, enterprise);

        var report = service.check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "delivery-api").message()).contains("GitHub.com only");
        verifyNoInteractions(jira);
        verify(probe, never()).requireDeliveryAccess(any(), anyString());
    }

    @Test
    void theDefaultModelHasASafeEffectiveConfiguration() {
        settings = new DevRuntimeProperties(Map.of(), null, 1800, null, null);

        var report = service().check(null);

        assertThat(report.ready()).isFalse();
        assertThat(report.runtime())
                .containsEntry("api", "anthropic-messages")
                .containsEntry("authentication", "bearer-token")
                .containsEntry("endpointOrigin", "http://gateway.fixture.invalid:4001");
        assertThat(check(report, "runtime").status()).isEqualTo(PASS);
        assertSafe(report);
    }

    @Test
    void invalidDestinationCannotReachDeliveryProbe() {
        repositories = catalog(Map.of("fixture", repository("source/module", false)));

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "delivery:fixture").status()).isEqualTo(FAIL);
        verify(probe, never()).requireDeliveryAccess(any(), anyString());
    }

    @Test
    void ambiguousJiraMappingCanOpenAChoiceWhenOneOptionIsNotReady() {
        repositories = catalog(Map.of("fixture", repository("source/module", true),
                "second", repository("source/other-module", true)));
        doThrow(new IllegalStateException("github-fixture-secret"))
                .when(probe).sourceBase(anyString(), eq("source/other-module"), anyString());

        var report = service().check("TASK-1");

        assertThat(report.ready()).isTrue();
        assertThat(check(report, "delivery:fixture").status()).isEqualTo(PASS);
        assertThat(check(report, "source:second").status()).isEqualTo(WARN);
        assertThat(check(report, "repository-choice").status()).isEqualTo(NOT_RUN);
        verify(probe).sourceBase("https://github.com", "source/module", "campaign/base");
        verify(probe).sourceBase("https://github.com", "source/other-module", "campaign/base");
        assertSafe(report);
        var payload = JSON.readTree("{\"issueKey\":\"TASK-1\"}");
        assertThatCode(() -> service().requireReady("dev-factory", payload)).doesNotThrowAnyException();
        var codec = new FrontmatterCodec();
        var intake = new IntakeWorker(jira, new RepositoryPolicy(repositories), codec, coding, mock(AuditLog.class),
                new TaskGuard(TaskAttemptPolicy.none(), Clock.systemUTC()));
        var result = intake.execute(new AgentContext(UUID.randomUUID(),
                "read-task", Map.of(), payload, Map.of(), List.of()));
        var decision = DecisionArtifacts.parseRequest(codec, result.outputs().get(DecisionArtifacts.REQUEST));

        assertThat(decision.required()).isTrue();
        assertThat(decision.options()).extracting(DecisionArtifacts.Option::id)
                .containsExactly("fixture", "second");
    }

    @Test
    void ambiguousMappingWithNoReadyOptionIsStillBlocked() {
        repositories = catalog(Map.of("fixture", repository("source/module", false),
                "second", repository("source/other-module", false)));

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        assertThat(check(report, "delivery:fixture").status()).isEqualTo(FAIL);
        assertThat(check(report, "delivery:second").status()).isEqualTo(FAIL);
    }

    @Test
    void disabledSelectionCannotPassTheGuardEvenWhenAnotherCandidateWasReady() {
        repositories = catalog(Map.of("fixture", repository("source/module", true),
                "second", repository("source/other-module", false)));
        var service = service();
        assertThat(service.check("TASK-1").ready()).isTrue();
        assertThatThrownBy(() -> service.requireRepositoryReady("second", SHA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Selected repository preflight failed");
    }

    @Test
    void selectedGuardChecksTheFrozenBaseWithoutReadingJiraOrUsingTodaysSourceHead() {
        String frozenBase = "b".repeat(40);

        service().requireRepositoryReady("fixture", frozenBase);

        verify(probe).requireDeliveryAccess(new DeliveryTarget("fork/module", "campaign/base"), frozenBase);
        verify(probe, never()).sourceBase(anyString(), anyString(), anyString());
        verifyNoInteractions(jira);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jira", "source", "delivery"})
    void externalFailuresAreReportedWithoutSerializingRemoteBodiesOrCredentials(String stage) {
        var failure = new IllegalStateException("upstream payload jira-fixture-secret github-fixture-secret coding-fixture-secret");
        switch (stage) {
            case "jira" -> when(jira.getIssue("TASK-1")).thenThrow(failure);
            case "source" -> when(probe.sourceBase(anyString(), anyString(), anyString())).thenThrow(failure);
            case "delivery" -> doThrow(failure).when(probe).requireDeliveryAccess(any(), anyString());
            default -> throw new AssertionError(stage);
        }

        var report = service().check("TASK-1");

        assertThat(report.ready()).isFalse();
        String checkName = stage.equals("jira") ? "jira" : stage + ":fixture";
        assertThat(check(report, checkName).status()).isEqualTo(FAIL);
        assertSafe(report);
    }

    private DeveloperPreflight service() {
        return service(coding, github);
    }

    private DeveloperPreflight service(CodingRuntime runtime, GitHubProperties gitHub) {
        var models = models();
        return new DeveloperPreflight(repositories, settings, runtime, jiraProperties, jira, gitHub, probe, models,
                new ModelChecks(models, probe));
    }

    private ModelCatalog models() {
        return new ModelCatalog(new ModelCatalog.Properties(List.of(defaultModel, GLM), null),
                settings.coding().runtime(), secrets::get);
    }

    private static ModelEntry model(String baseUrl, ModelPrice price) {
        return new ModelEntry("fixture", "anthropic-messages", baseUrl, "bearer-token", "CODING_SECRET", "exact-model",
                List.of("low", "medium", "high", "xhigh", "max"), "high", null, price);
    }

    private void usePiProfile(String baseUrl) {
        var pi = new DevRuntimeProperties.Pi("coding:image", "fixture-provider", "exact-model", baseUrl,
                "openai-completions", "coding-fixture-secret", "api-key", null, 16384);
        var runtime = new DevRuntimeProperties.Coding("pi", pi, null);
        settings = new DevRuntimeProperties(settings.plans(), runtime, 1800, null, null);
        when(coding.id()).thenReturn("pi");
        when(coding.identity(any())).thenReturn(Map.of("runtime", "pi", "model", "exact-model"));
    }

    private static DeveloperPreflight.Check check(DeveloperPreflight.Report report, String name) {
        return report.checks().stream()
                .filter(check -> check.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSafe(DeveloperPreflight.Report report) {
        assertThat(JSON.writeValueAsString(report)).doesNotContain("jira-fixture-secret", "github-fixture-secret",
                "coding-fixture-secret", "upstream payload");
    }

    private static DevFactoryProperties catalog(Map<String, DevFactoryProperties.Repository> entries) {
        return new DevFactoryProperties("https://github.com", new TreeMap<>(entries));
    }

    private static DevFactoryProperties.Repository repository(String source, boolean deliverable) {
        return new DevFactoryProperties.Repository(source, "campaign/base", "build:image", "unit", List.of("TASK"),
                List.of(), new DeliveryTarget(deliverable ? "fork/module" : "not-a-repository", null));
    }

    private static JiraIssue issue(String key) {
        return new JiraIssue(key, "Fixture task", "Fixture description", "Open", "Task", List.of(), null);
    }
}
