package org.folio.factory.app.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.InboxTaskFileParser;
import org.folio.factory.devfactory.jira.JiraIntakeException;
import org.folio.factory.devfactory.jira.JiraTaskService;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin diagnostic boundary; resolution is owned by the deterministic domain service. */
@RestController
@RequestMapping("/api/dev/tasks")
public class DevTaskController {
  private final InboxTaskFileParser parser;
  private final TaskResolutionService resolver;
  private final JiraTaskService jiraTasks;

  public DevTaskController(InboxTaskFileParser parser, TaskResolutionService resolver,
                           JiraTaskService jiraTasks) {
    this.parser = parser;
    this.resolver = resolver;
    this.jiraTasks = jiraTasks;
  }

  @PostMapping(value = "/resolve", consumes = {
      "application/yaml", "application/x-yaml", MediaType.APPLICATION_JSON_VALUE,
      MediaType.TEXT_PLAIN_VALUE}, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResolvedIntent resolve(@RequestBody byte[] content,
                                @RequestHeader(value = "X-Task-Name", defaultValue = "diagnostic")
                                String taskName) {
    TaskRequest request = parser.parseBytes(content, taskName);
    return resolver.resolve(request);
  }

  /**
   * Starts the Developer Flow from a Jira issue key. 202 when an execution was
   * admitted (or an identical earlier admission was returned); 422 when the
   * task resolved but was not admitted (for example no approved repository).
   */
  @PostMapping(value = "/jira", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JiraTaskService.RunResult> startJira(@RequestBody JiraTaskService.RunRequest request) {
    JiraTaskService.RunResult result = jiraTasks.start(request);
    return ResponseEntity.status(result.admitted() ? HttpStatus.ACCEPTED : HttpStatus.UNPROCESSABLE_CONTENT)
        .body(result);
  }

  @ExceptionHandler(JiraIntakeException.class)
  public ResponseEntity<Map<String, Object>> jiraIntakeFailed(JiraIntakeException e) {
    HttpStatus status = switch (e.code()) {
      case JiraIntakeException.INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
      case JiraIntakeException.ISSUE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case JiraIntakeException.JIRA_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
      case JiraIntakeException.JIRA_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
      default -> HttpStatus.SERVICE_UNAVAILABLE;
    };
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", e.getMessage());
    body.put("code", e.code());
    body.put("retryable", e.transientFailure());
    return ResponseEntity.status(status).body(body);
  }
}
