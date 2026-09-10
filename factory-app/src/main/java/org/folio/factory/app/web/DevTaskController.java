package org.folio.factory.app.web;

import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.InboxTaskFileParser;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.springframework.http.MediaType;
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

  public DevTaskController(InboxTaskFileParser parser, TaskResolutionService resolver) {
    this.parser = parser;
    this.resolver = resolver;
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
}
