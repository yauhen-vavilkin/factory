package org.folio.factory.devfactory.inbox;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scans the dev-factory inbox directory on a fixed cadence, parses each
 * top-level yaml task file, routes a {@code file.inbox} trigger event and
 * claims the file by moving it to {@code processed/} (at least one execution
 * admitted) or {@code failed/} (parse/validation failure, empty route result
 * or route exception).
 *
 * <p>T22 idempotent admission: the event is admitted under
 * {@link FileInboxAdmissionKey the content-derived admission key} via
 * {@link PipelineRouter#routeAdmitted}. Route-before-claim is still the
 * order, so a crash or failed claim move between the database commit and the
 * file move simply re-routes the same admission on the next poll — the
 * router returns the already-admitted execution instead of duplicating it.
 */
@Component
@ConditionalOnProperty(prefix = "factory.inbox", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class FileInboxTrigger {

    private static final Logger log = LoggerFactory.getLogger(FileInboxTrigger.class);
    private static final String EVENT_TYPE = "file.inbox";
    private static final String PROCESSED_DIR = "processed";
    private static final String FAILED_DIR = "failed";

    private final InboxProperties properties;
    private final InboxTaskFileParser parser;
    private final PipelineRouter router;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public FileInboxTrigger(InboxProperties properties, InboxTaskFileParser parser,
                            PipelineRouter router) {
        this.properties = properties;
        this.parser = parser;
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
            log.debug("Inbox directory {} does not exist yet; nothing to do", inbox);
            return;
        }
        for (Path file : yamlFiles(inbox)) {
            try {
                process(file);
            } catch (RuntimeException e) {
                log.warn("Task file {} could not be processed: {}", file.getFileName(), e.getMessage());
            }
        }
    }

    private void process(Path file) {
        String fileName = file.getFileName().toString();
        InboxTask task;
        try {
            task = parser.parse(file);
        } catch (InboxTaskFileException e) {
            log.warn("Task file {} rejected: {}", fileName, e.getMessage());
            claim(file, FAILED_DIR);
            return;
        }
        try {
            JsonNode payload = payload(task);
            String admissionKey = FileInboxAdmissionKey.of(payload);
            TriggerEvent event = TriggerEvent.of(EVENT_TYPE, "file-inbox:" + fileName, payload);
            List<UUID> admitted = router.routeAdmitted(event, admissionKey);
            claim(file, admitted == null || admitted.isEmpty() ? FAILED_DIR : PROCESSED_DIR);
        } catch (RuntimeException e) {
            log.warn("Routing failed for task file {}: {}", fileName, e.getMessage());
            claim(file, FAILED_DIR);
        }
    }

    private JsonNode payload(InboxTask task) {
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("taskId", task.id());
        payload.put("repoUrl", task.repo());
        payload.put("baseBranch", task.base());
        payload.put("branch", task.branch());
        payload.put("goal", task.goal());
        ArrayNode acceptance = payload.putArray("acceptance");
        for (String item : task.acceptance()) {
            acceptance.add(item);
        }
        ObjectNode constraints = payload.putObject("constraints");
        for (Map.Entry<String, Object> entry : task.constraints().entrySet()) {
            constraints.set(entry.getKey(), jsonMapper.valueToTree(entry.getValue()));
        }
        payload.set("notes", task.notes() == null ? NullNode.instance
                : jsonMapper.valueToTree(task.notes()));
        return payload;
    }

    private static List<Path> yamlFiles(Path inbox) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                    files.add(entry);
                }
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private void claim(Path file, String targetDirName) {
        try {
            Path targetDir = properties.dir().resolve(targetDirName);
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(file.getFileName().toString());
            if (Files.exists(target)) {
                target = targetDir.resolve(disambiguated(file.getFileName().toString()));
            }
            try {
                Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, target);
            }
            log.info("Claimed task file {} -> {}", file.getFileName(), targetDirName);
        } catch (IOException e) {
            log.warn("Could not move task file {} to {}: {}", file.getFileName(), targetDirName,
                    e.getMessage());
        }
    }

    private static String disambiguated(String fileName) {
        long stamp = System.currentTimeMillis();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName + "-" + stamp;
        }
        return fileName.substring(0, dot) + "-" + stamp + fileName.substring(dot);
    }
}
