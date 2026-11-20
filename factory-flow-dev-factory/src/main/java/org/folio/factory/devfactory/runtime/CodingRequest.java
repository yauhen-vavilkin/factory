package org.folio.factory.devfactory.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Factory-owned, runtime-neutral description of one authorized coding attempt. */
public record CodingRequest(
        String issueKey,
        String summary,
        String description,
        List<Comment> comments,
        List<LinkedIssue> linkedIssues,
        List<String> acceptanceCriteria,
        List<ConfirmedDecision> confirmedDecisions,
        RepositoryTarget repository,
        Constraints constraints,
        RunSettings runSettings,
        List<Attachment> attachments) {

    // Requests stored before attachments existed have no such field, so a missing list reads as empty.
    public CodingRequest {
        issueKey = text(issueKey);
        summary = text(summary);
        description = text(description);
        comments = comments == null ? List.of() : List.copyOf(comments);
        linkedIssues = linkedIssues == null ? List.of() : List.copyOf(linkedIssues);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        confirmedDecisions = confirmedDecisions == null ? List.of() : List.copyOf(confirmedDecisions);
        constraints = constraints == null ? Constraints.defaults() : constraints;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public CodingRequest withDecision(ConfirmedDecision decision) {
        java.util.Objects.requireNonNull(decision, "decision");
        var decisions = new java.util.ArrayList<>(confirmedDecisions);
        decisions.add(decision);
        return new CodingRequest(issueKey, summary, description, comments, linkedIssues,
                acceptanceCriteria, decisions, repository, constraints, runSettings, attachments);
    }

    /** Tells the agent how Factory will verify the work and whether the workspace is already prepared. */
    public CodingRequest withVerification(String command, boolean dependenciesInstalled) {
        return new CodingRequest(issueKey, summary, description, comments, linkedIssues,
                acceptanceCriteria, confirmedDecisions, repository,
                constraints.withVerification(command, dependenciesInstalled), runSettings, attachments);
    }

    /** The same request with the attachments resolved for this attempt (staged files or reasons they were not). */
    public CodingRequest withAttachments(List<Attachment> resolved) {
        return new CodingRequest(issueKey, summary, description, comments, linkedIssues,
                acceptanceCriteria, confirmedDecisions, repository, constraints, runSettings, resolved);
    }

    public void requireReady() {
        if (issueKey.isBlank() || summary.isBlank() || repository == null
                || repository.key().isBlank() || repository.sourceRepo().isBlank()
                || repository.baseBranch().isBlank()
                || !repository.baseSha().matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException("Coding request is not ready");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public record Comment(String author, String created, String body) {
        public Comment { author = text(author); created = text(created); body = text(body); }
    }

    public record LinkedIssue(String relation, String key, String summary, String status) {
        public LinkedIssue {
            relation = text(relation); key = text(key); summary = text(summary); status = text(status);
        }
    }

    /**
     * An image attached to the ticket. {@code path} is where this attempt's workload holds the file and is blank
     * in the stored request; {@code note} says why an attachment was not provided.
     */
    public record Attachment(String id, String filename, String mimeType, long size, String path, String note) {
        public Attachment {
            id = text(id); filename = text(filename); mimeType = text(mimeType); path = text(path); note = text(note);
        }

        /** {@code bytes} is the size of the file actually staged, which can differ from what Jira reported. */
        public Attachment staged(String path, long bytes) {
            return new Attachment(id, filename, mimeType, bytes, path, "");
        }

        public Attachment skipped(String note) {
            return new Attachment(id, filename, mimeType, size, "", note);
        }
    }

    public record ConfirmedDecision(String requestId, String kind, String question, String answer) {
        public ConfirmedDecision {
            requestId = text(requestId); kind = text(kind); question = text(question); answer = text(answer);
        }
    }

    public record RepositoryTarget(String key, String sourceRepo, String baseBranch, String baseSha) {
        public RepositoryTarget {
            key = text(key); sourceRepo = text(sourceRepo); baseBranch = text(baseBranch); baseSha = text(baseSha);
        }
    }

    /**
     * What the person chose for this run: the model from config/developer-models.yaml, its effort and extra
     * instructions. A null field means the default; a request without settings uses the default model. It never
     * holds a secret. Requests stored before 2026-11-03 name the model "profileId" and also hold maxTurns, which
     * is now a limit in .env; the JSON name stays so that open pull requests still get their feedback runs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunSettings(@JsonProperty("profileId") String modelId, String effort, String instructions) {
    }

    /**
     * {@code verificationCommand} and {@code dependenciesInstalled} tell the agent how Factory checks the work.
     * The flag is boxed so that requests stored before these fields existed still parse.
     */
    public record Constraints(String workingDirectory, boolean allowRepositorySwitch, boolean allowPush,
                              boolean allowExternalWrites, boolean allowGitMetadataChanges,
                              boolean independentVerification, String verificationCommand,
                              Boolean dependenciesInstalled) {
        public Constraints(String workingDirectory, boolean allowRepositorySwitch, boolean allowPush,
                           boolean allowExternalWrites, boolean allowGitMetadataChanges,
                           boolean independentVerification) {
            this(workingDirectory, allowRepositorySwitch, allowPush, allowExternalWrites,
                    allowGitMetadataChanges, independentVerification, "", false);
        }

        public Constraints {
            verificationCommand = text(verificationCommand);
            dependenciesInstalled = Boolean.TRUE.equals(dependenciesInstalled);
        }

        public static Constraints defaults() {
            return new Constraints("/workspace", false, false, false, false, true);
        }

        Constraints withVerification(String command, boolean installed) {
            return new Constraints(workingDirectory, allowRepositorySwitch, allowPush, allowExternalWrites,
                    allowGitMetadataChanges, independentVerification, command, installed);
        }
    }
}
