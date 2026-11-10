package org.folio.factory.devfactory.feedback;

/** The one GitHub write of a PR feedback round, kept apart from the read-only {@link PrFeedbackGateway}. */
public interface PrCommentWriter {

    /** False when no GitHub token is set; the round then skips its comment. */
    boolean configured();

    /** Posts a comment on the pull request and returns its URL. */
    String post(PrFeedbackGateway.PullRequest pr, String body);
}
