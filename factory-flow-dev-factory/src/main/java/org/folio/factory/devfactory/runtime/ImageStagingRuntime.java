package org.folio.factory.devfactory.runtime;

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
    @Override public void requireConfigured() { delegate.requireConfigured(); }
    @Override public Map<String, Object> identity() { return delegate.identity(); }
    @Override public boolean acceptsImages() { return delegate.acceptsImages(); }
    @Override public String redact(String value) { return delegate.redact(value); }

    @Override
    public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds) {
        return code(workload, request, timeoutSeconds, event -> {});
    }

    @Override
    public CodingOutcome code(DockerWorkloads.Workload workload, CodingRequest request, int timeoutSeconds,
                              Consumer<Map<String, Object>> progress) {
        return delegate.code(workload, stage(workload, request, progress), timeoutSeconds, progress);
    }

    CodingRequest stage(DockerWorkloads.Workload workload, CodingRequest request, Consumer<Map<String, Object>> progress) {
        if (request.attachments().isEmpty()) return request;
        if (!delegate.acceptsImages()) {
            return request.withAttachments(request.attachments().stream()
                    .map(a -> a.skipped("the configured coding model is not set up to receive images")).toList());
        }
        var temporary = CandidateFreezer.temporary("factory-dev-images-");
        try {
            var resolved = new ArrayList<CodingRequest.Attachment>();
            var files = new ArrayList<Map<String, Object>>();
            for (var attachment : request.attachments()) {
                resolved.add(resolve(attachment, files.size(), temporary, files));
            }
            if (!files.isEmpty()) {
                try {
                    workload.execute(List.of("mkdir", "-p", DIRECTORY), 30).requireSuccess();
                    workload.copy(temporary.resolve(".").toString(), DIRECTORY);
                } catch (RuntimeException e) {
                    files.clear();
                    resolved.replaceAll(a -> a.path().isBlank() ? a : a.skipped("could not be copied into the workload"));
                }
            }
            var event = new LinkedHashMap<String, Object>();
            event.put("activity", "attachments_staged");
            event.put("images", files);
            event.put("notProvided", resolved.stream().filter(a -> !a.note().isBlank())
                    .map(a -> a.filename() + " " + a.note()).toList());
            progress.accept(event);
            return request.withAttachments(resolved);
        } finally {
            CandidateFreezer.cleanup(temporary);
        }
    }

    private CodingRequest.Attachment resolve(CodingRequest.Attachment attachment, int stagedSoFar,
                                             java.nio.file.Path directory, List<Map<String, Object>> files) {
        if (limits.maxImages() > 0 && stagedSoFar >= limits.maxImages())
            return attachment.skipped("was not provided: only the first " + limits.maxImages() + " images are passed on");
        if (attachment.size() > limits.maxBytes())
            return attachment.skipped("was not provided: it is larger than " + limits.maxBytes() + " bytes");
        byte[] bytes;
        try {
            bytes = jira.downloadAttachment(attachment.id(), limits.maxBytes());
        } catch (RuntimeException e) {
            return attachment.skipped("could not be downloaded from Jira");
        }
        var format = ImageFormat.detect(bytes);
        if (format.isEmpty())
            return attachment.skipped("was not provided: its content is not a png, jpeg, gif or webp image");
        String name = "img-" + (stagedSoFar + 1) + "." + format.get().extension();
        try {
            Files.write(directory.resolve(name), bytes);
        } catch (IOException e) {
            return attachment.skipped("could not be prepared");
        }
        var file = new LinkedHashMap<String, Object>();
        file.put("filename", attachment.filename());
        file.put("bytes", bytes.length);
        file.put("sha256", sha256(bytes));
        files.add(file);
        return attachment.staged(DIRECTORY + "/" + name, bytes.length);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
