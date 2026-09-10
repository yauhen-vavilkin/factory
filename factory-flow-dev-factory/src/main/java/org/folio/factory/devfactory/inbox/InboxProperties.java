package org.folio.factory.devfactory.inbox;

import java.nio.file.Path;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.inbox")
public record InboxProperties(Boolean enabled, Path dir, Long pollIntervalMs, String eventType) {
  public InboxProperties(Boolean enabled, Path dir, Long pollIntervalMs) {
    this(enabled, dir, pollIntervalMs, "file.inbox");
  }
  @ConstructorBinding
  public InboxProperties(Boolean enabled, Path dir, Long pollIntervalMs, String eventType) {
    enabled = enabled == null || enabled;
    dir = dir == null ? Path.of("tasks-inbox") : dir;
    pollIntervalMs = pollIntervalMs == null ? 5000L : pollIntervalMs;
    eventType = eventType == null || eventType.isBlank() ? "file.inbox" : eventType;
    this.enabled = enabled;
    this.dir = dir;
    this.pollIntervalMs = pollIntervalMs;
    this.eventType = eventType;
  }
}
