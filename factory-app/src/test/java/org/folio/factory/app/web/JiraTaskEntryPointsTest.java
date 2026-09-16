package org.folio.factory.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.folio.factory.devfactory.jira.JiraIntakeException;
import org.folio.factory.devfactory.jira.JiraTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import tools.jackson.databind.json.JsonMapper;

/** The REST API, the UI form and the CLI all start Jira tasks through one application service. */
class JiraTaskEntryPointsTest {
  private final JiraTaskService service = mock(JiraTaskService.class);
  private final JiraTaskService.RunRequest request =
      new JiraTaskService.RunRequest("MODSIDECAR-207", "LOCAL_ONLY", null, null);

  @Test
  void apiDelegatesToTheServiceAndMapsOutcomes() {
    UUID execution = UUID.randomUUID();
    when(service.start(request)).thenReturn(result(execution, "ADMITTED"));
    DevTaskController controller = new DevTaskController(null, null, service);

    assertThat(controller.startJira(request).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    when(service.start(request)).thenReturn(result(null, "BLOCKED"));
    assertThat(controller.startJira(request).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(controller.jiraIntakeFailed(new JiraIntakeException(JiraIntakeException.ISSUE_NOT_FOUND, "x"))
        .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(controller.jiraIntakeFailed(new JiraIntakeException(JiraIntakeException.JIRA_NOT_CONFIGURED, "x"))
        .getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void uiFormUsesTheSameServiceAndRedirectsToTheAdmittedExecution() {
    UUID execution = UUID.randomUUID();
    when(service.start(any())).thenReturn(result(execution, "ADMITTED"));
    UiController ui = new UiController(null, null, null, null, null, JsonMapper.builder().build(), service);

    String view = ui.runJira("MODSIDECAR-207", "LOCAL_ONLY", "", "", new ExtendedModelMap());

    assertThat(view).isEqualTo("redirect:/executions/" + execution);
    verify(service).start(new JiraTaskService.RunRequest("MODSIDECAR-207", "LOCAL_ONLY", "", ""));

    when(service.start(any())).thenThrow(new JiraIntakeException(JiraIntakeException.ISSUE_NOT_FOUND, "missing"));
    ExtendedModelMap model = new ExtendedModelMap();
    assertThat(ui.runJira("MODSIDECAR-999", "LOCAL_ONLY", null, null, model)).isEqualTo("jira-run");
    assertThat(model.getAttribute("errorCode")).isEqualTo(JiraIntakeException.ISSUE_NOT_FOUND);
  }

  @Test
  void cliOnlyForwardsToTheFactoryApi() throws Exception {
    String script = Files.readString(Path.of("..", "scripts", "factory"));
    String runJira = script.substring(script.indexOf("run_jira() {"), script.indexOf("show_execution() {"));

    assertThat(runJira).contains("/api/dev/tasks/jira")
        .doesNotContain("rest/api", "atlassian", "JIRA_API_TOKEN", "FACTORY_CONNECTORS_JIRA");
    assertThat(script).contains("run-jira) run_jira \"$@\" ;;");
  }

  private static JiraTaskService.RunResult result(UUID execution, String outcome) {
    return new JiraTaskService.RunResult("MODSIDECAR-207", outcome, null, outcome, execution, false,
        "folio-org/folio-module-sidecar", List.of("folio-org/folio-module-sidecar"), "master",
        "0123456789abcdef0123456789abcdef01234567", "java-maven-verify", "abc", "/tmp/s.json", "hash");
  }
}
