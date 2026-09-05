package org.folio.factory.devfactory.inbox;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.inbox")
public record InboxProperties(Boolean enabled, Path dir, Long pollIntervalMs) {
  public InboxProperties {
    enabled = enabled == null || enabled;
    dir = dir == null ? Path.of("tasks-inbox") : dir;
    pollIntervalMs = pollIntervalMs == null ? 5000L : pollIntervalMs;
  }
}
