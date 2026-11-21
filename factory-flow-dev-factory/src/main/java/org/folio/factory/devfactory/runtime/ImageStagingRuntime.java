package org.folio.factory.devfactory.runtime;

import lombok.extern.slf4j.Slf4j;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.devfactory.candidate.CandidateFreezer;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Downloads the ticket's images into the coding workload before the wrapped runtime starts, so the stored
 * request stays free of file contents and every attempt (first, continued, retried) sees the attachments as
 * they are in Jira at that time. The files go outside {@code /workspace}, so they never reach the candidate.
 * An image that cannot be provided is recorded in the request with the reason, never a reason to fail the attempt.
 */
@Slf4j
public final class ImageStagingRuntime implements CodingRuntime {
    static final String DIRECTORY = "/tmp/factory-attachments";

    private final CodingRuntime delegate;
    private final JiraConnector jira;
    private final DevAttachmentProperties limits;

    public ImageStagingRuntime(CodingRuntime delegate, JiraConnector jira, DevAttachmentProperties limits) {
        this.delegate = delegate;
        this.jira = jira;
        this.limits = limits;
    }

    @Override public String id() { return delegate.id(); }
    @Override public String image() { return delegate.image(); }
    @Override public void requireConfigured(CodingRequest.RunSettings settings) {
        delegate.requireConfigured(settings);
    }
    @Override public Map<String, Object> identity(CodingRequest.RunSettings settings) {
        return delegate.identity(settings);
    }
    @Override public boolean acceptsImages(CodingRequest.RunSettings settings) {
        return delegate.acceptsImages(settings);
    }
    @Override public String redact(String value) { return delegate.redact(value); }
    @Override public boolean holdsSecret(String text) { return delegate.holdsSecret(text); }

    @Override
    public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                              Consumer<Map<String, Object>> progress, CodingTranscripts.Sink transcript) {
        return delegate.code(workload, stage(workload, request, progress), timeoutSeconds, progress, transcript);
    }

    CodingRequest stage(DockerWorkloads.Workload workload, CodingRequest request,
                        Consumer<Map<String, Object>> progress) {
        if (request.attachments().isEmpty()) return request;
        if (!delegate.acceptsImages(request.runSettings())) {
            return request.withAttachments(request.attachments().stream()
                    .map(a -> a.skipped("the chosen coding model is not set up to receive images")).toList());
        }
        var temporary = CandidateFreezer.temporary("factory-dev-images-");
        try {
            var files = new ArrayList<Map<String, Object>>();
            var resolved = new ArrayList<CodingRequest.Attachment>();
            for (var attachment : request.attachments()) {
                resolved.add(resolve(attachment, files.size(), temporary, files));
            }
            copyInto(workload, temporary, files, resolved);
            reportStaged(progress, stagedEvent(files, resolved));
            return request.withAttachments(resolved);
        } finally {
            CandidateFreezer.cleanup(temporary);
        }
    }

    /** A failed copy reports every image as not provided, so the agent is never told about a missing file. */
    private static void copyInto(DockerWorkloads.Workload workload, java.nio.file.Path temporary,
                                 List<Map<String, Object>> files, List<CodingRequest.Attachment> resolved) {
        if (files.isEmpty()) return;
        try {
            workload.execute(List.of("mkdir", "-p", DIRECTORY), 30, Processes.OUTPUT_LIMIT, null).requireSuccess();
            workload.copy(temporary.resolve(".").toString(), DIRECTORY);
        } catch (RuntimeException e) {
            files.clear();
            resolved.replaceAll(a -> a.path().isBlank() ? a : a.skipped("could not be copied into the workload"));
        }
    }

    /** The progress consumer may throw, for example when its audit write fails; that must not stop the attempt. */
    private void reportStaged(Consumer<Map<String, Object>> progress, Map<String, Object> event) {
        try {
            progress.accept(event);
        } catch (RuntimeException e) {
            log.warn("Could not save the staged images event: {}", delegate.redact(String.valueOf(e.getMessage())));
        }
    }

    private static Map<String, Object> stagedEvent(List<Map<String, Object>> files,
                                                   List<CodingRequest.Attachment> resolved) {
        var event = new LinkedHashMap<String, Object>();
        event.put("activity", "attachments_staged");
        event.put("images", files);
        event.put("notProvided", resolved.stream().filter(a -> !a.note().isBlank())
                .map(a -> a.filename() + " " + a.note()).toList());
        return event;
    }

    private CodingRequest.Attachment resolve(CodingRequest.Attachment attachment, int stagedSoFar,
                                             java.nio.file.Path directory, List<Map<String, Object>> files) {
        var limit = limitReason(attachment, stagedSoFar);
        if (limit.isPresent()) return attachment.skipped(limit.get());
        byte[] bytes;
        try {
            bytes = jira.downloadAttachment(attachment.id(), limits.maxBytes());
        } catch (RuntimeException e) {
            return attachment.skipped("could not be downloaded from Jira");
        }
        var format = ImageFormat.detect(bytes);
        if (format.isEmpty()) {
            return attachment.skipped("was not provided: its content is not a png, jpeg, gif or webp image");
        }
        String name = "img-" + (stagedSoFar + 1) + "." + format.get().extension();
        try {
            Files.write(directory.resolve(name), bytes);
        } catch (IOException e) {
            return attachment.skipped("could not be prepared");
        }
        files.add(fileRecord(attachment, bytes));
        return attachment.staged(DIRECTORY + "/" + name, bytes.length);
    }

    private java.util.Optional<String> limitReason(CodingRequest.Attachment attachment, int stagedSoFar) {
        if (limits.maxImages() > 0 && stagedSoFar >= limits.maxImages()) {
            return java.util.Optional.of("was not provided: only the first " + limits.maxImages()
                    + " images are passed on");
        }
        if (attachment.size() > limits.maxBytes()) {
            return java.util.Optional.of("was not provided: it is larger than " + limits.maxBytes() + " bytes");
        }
        return java.util.Optional.empty();
    }

    private static Map<String, Object> fileRecord(CodingRequest.Attachment attachment, byte[] bytes) {
        var file = new LinkedHashMap<String, Object>();
        file.put("filename", attachment.filename());
        file.put("bytes", bytes.length);
        file.put("sha256", sha256(bytes));
        return file;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
