package org.folio.factory.devfactory.inbox;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.resolution.RepositoryAccessException;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Atomic file-inbox admission with durable reject/block receipts. */
@Component
@ConditionalOnProperty(prefix = "factory.inbox", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class FileInboxTrigger {
  private static final Logger log = LoggerFactory.getLogger(FileInboxTrigger.class);

  private final InboxProperties properties;
  private final InboxTaskFileParser parser;
  private final TaskResolutionService resolver;
  private final PipelineRouter router;
  private final JsonMapper json = JsonMapper.builder().build();

  public FileInboxTrigger(InboxProperties properties, InboxTaskFileParser parser,
                          TaskResolutionService resolver, PipelineRouter router) {
    this.properties = properties;
    this.parser = parser;
    this.resolver = resolver;
    this.router = router;
  }

  @Scheduled(fixedDelayString = "${factory.inbox.poll-interval-ms:5000}")
  public void poll() {
    try {
      scanAndRoute();
    } catch (IOException | RuntimeException e) {
      log.warn("Inbox poll on {} failed: {}", properties.dir(), e.getMessage());
    }
  }

  private void scanAndRoute() throws IOException {
    Path inbox = properties.dir();
    if (!Files.isDirectory(inbox)) {
      return;
    }
    for (Path file : taskFiles(inbox)) {
      process(file);
    }
  }

  private void process(Path file) {
    String fileName = file.getFileName().toString();
    TaskRequest task;
    try {
      task = parser.parse(file);
    } catch (InboxTaskFileException | IllegalArgumentException e) {
      reject(file, "INVALID_TASK", e.getMessage());
      return;
    }

    ResolvedIntent intent;
    try {
      intent = resolver.resolve(task);
    } catch (RepositoryAccessException | TransientDataAccessException e) {
      // A valid task remains in the inbox. A later poll can safely retry it.
      log.warn("Transient resolution/admission failure for {}: {}", fileName, e.getMessage());
      return;
    } catch (IllegalArgumentException e) {
      reject(file, "INVALID_TASK", e.getMessage());
      return;
    }
    if ("BLOCKED".equals(intent.status())) {
      receiptAndClaim(file, "blocked", intent.code(), intent.message(), intent, null);
      return;
    }

    try {
      JsonNode payload = payload(intent);
      TriggerEvent event = TriggerEvent.of(properties.eventType(), "file-inbox:" + fileName, payload);
      List<UUID> admitted = router.routeAdmitted(event, intent.admissionKey());
      if (admitted == null || admitted.isEmpty()) {
        reject(file, "NO_MATCHING_FLOW", "No flow accepted the resolved task");
        return;
      }
      receiptAndClaim(file, "processed", "ADMITTED", "Task admitted", intent, admitted.getFirst());
    } catch (TransientDataAccessException e) {
      log.warn("Transient database admission failure for {}: {}", fileName, e.getMessage());
    } catch (RuntimeException e) {
      // Routing/infrastructure failures are not evidence that a valid task is permanently invalid.
      log.warn("Routing failed for valid task {} and remains retryable: {}", fileName, e.getMessage());
    }
  }

  private JsonNode payload(ResolvedIntent intent) {
    TaskRequest task = intent.task();
    ObjectNode payload = json.createObjectNode();
    payload.put("taskId", task.source().id());
    payload.put("repoUrl", intent.repository().origin());
    payload.put("baseBranch", intent.repository().exactRevision());
    payload.put("baseRevision", intent.repository().exactRevision());
    payload.put("branch", deliveryBranch(task));
    payload.put("goal", task.goal());
    ArrayNode acceptance = payload.putArray("acceptance");
    task.acceptanceCriteria().forEach(item -> acceptance.add(item.text()));
    payload.set("acceptanceCriteria", json.valueToTree(task.acceptanceCriteria()));
    payload.set("constraints", task.constraints());
    if (task.notes() == null) {
      payload.putNull("notes");
    } else {
      payload.put("notes", task.notes());
    }
    payload.put("rawTaskText", task.rawTaskText());
    payload.put("semanticTaskHash", intent.semanticTaskHash());
    payload.put("intentHash", intent.intentHash());
    payload.put("runKey", task.runKey());
    payload.set("resolvedIntent", json.valueToTree(intent));
    return payload;
  }

  private static String deliveryBranch(TaskRequest task) {
    JsonNode legacy = task.metadata().get("legacyDeliveryBranch");
    if (legacy != null && legacy.isTextual()) {
      return legacy.asString();
    }
    String normalized = task.source().id().replaceAll("[^A-Za-z0-9._-]", "-");
    return "task/" + normalized;
  }

  private void reject(Path file, String code, String message) {
    receiptAndClaim(file, "failed", code, message, null, null);
  }

  private void receiptAndClaim(Path file, String directory, String code, String message,
                               ResolvedIntent intent, UUID executionId) {
    try {
      Path targetDir = properties.dir().resolve(directory);
      Files.createDirectories(targetDir);
      Path target = uniqueTarget(targetDir, file.getFileName().toString());
      ObjectNode receipt = json.createObjectNode();
      receipt.put("receiptVersion", 1);
      receipt.put("status", "blocked".equals(directory) ? "BLOCKED"
          : "failed".equals(directory) ? "REJECTED" : "ADMITTED");
      receipt.put("code", code);
      receipt.put("message", message == null ? "" : message);
      receipt.put("sourceFile", file.getFileName().toString());
      receipt.put("recordedAt", Instant.now().toString());
      if (intent != null) {
        receipt.put("semanticTaskHash", intent.semanticTaskHash());
        receipt.put("intentHash", intent.intentHash());
        receipt.set("resolution", json.valueToTree(intent));
      }
      if (executionId != null) {
        receipt.put("executionId", executionId.toString());
      }
      atomicWrite(targetDir.resolve(target.getFileName() + ".receipt.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
      atomicMove(file, target);
      log.info("Task file {} -> {} ({})", file.getFileName(), directory, code);
    } catch (IOException e) {
      log.warn("Could not persist receipt/claim for {}: {}", file.getFileName(), e.getMessage());
    }
  }

  private static void atomicWrite(Path target, byte[] bytes) throws IOException {
    Path partial = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".partial");
    Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    atomicMove(partial, target);
  }

  private static void atomicMove(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      throw new IOException("atomic move is required for task admission", e);
    }
  }

  private static Path uniqueTarget(Path directory, String name) {
    Path target = directory.resolve(name);
    if (!Files.exists(target)) {
      return target;
    }
    int dot = name.lastIndexOf('.');
    String suffix = "-" + System.currentTimeMillis();
    return dot <= 0 ? directory.resolve(name + suffix)
        : directory.resolve(name.substring(0, dot) + suffix + name.substring(dot));
  }

  private static List<Path> taskFiles(Path inbox) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
      for (Path entry : stream) {
        String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) {
          files.add(entry);
        }
      }
    }
    files.sort(Comparator.comparing(path -> path.getFileName().toString()));
    return files;
  }
}
