package org.folio.factory.devfactory.feedback;

import java.util.List;

/** A read-only GitHub boundary. All destinations come from trusted delivery configuration. */
public interface PrFeedbackGateway {
    enum Status { OPEN, MERGED, CLOSED }
    record PullRequest(String repository, int number, String branch, String baseBranch, String headSha,
                       String url, boolean open) { }
    record Comment(String id, String author, String body, String path, Integer line,
                   boolean outdated, String updatedAt, String url) { }
    record Snapshot(String id, PullRequest pr, List<Comment> comments) { }
    PullRequest read(String repository, int number);
    /** Read terminal status without requiring a head repository after branch deletion. */
    Status status(PullRequest previous);
    Snapshot comments(PullRequest pr);
}
