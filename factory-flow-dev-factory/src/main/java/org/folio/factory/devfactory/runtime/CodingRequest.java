package org.folio.factory.devfactory.runtime;

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
        Constraints constraints) {

    public CodingRequest {
        issueKey = text(issueKey);
        summary = text(summary);
        description = text(description);
        comments = comments == null ? List.of() : List.copyOf(comments);
        linkedIssues = linkedIssues == null ? List.of() : List.copyOf(linkedIssues);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        confirmedDecisions = confirmedDecisions == null ? List.of() : List.copyOf(confirmedDecisions);
        constraints = constraints == null ? Constraints.defaults() : constraints;
    }

    public CodingRequest withDecision(ConfirmedDecision decision) {
        java.util.Objects.requireNonNull(decision, "decision");
        var decisions = new java.util.ArrayList<>(confirmedDecisions);
        decisions.add(decision);
        return new CodingRequest(issueKey, summary, description, comments, linkedIssues,
                acceptanceCriteria, decisions, repository, constraints);
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

    public record Constraints(String workingDirectory, boolean allowRepositorySwitch, boolean allowPush,
                              boolean allowExternalWrites, boolean allowGitMetadataChanges,
                              boolean independentVerification) {
        public static Constraints defaults() {
            return new Constraints("/workspace", false, false, false, false, true);
        }
    }
}
