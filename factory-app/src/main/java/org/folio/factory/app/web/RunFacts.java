package org.folio.factory.app.web;

/**
 * What the artifacts and the audit trail of one Developer Flow run say, before the run page puts it into words
 * for its parts. Text fields are empty, never null, when the run has no such fact.
 */
record RunFacts(String productStatus, Task task, String settings, String reason, ReasonKind reasonKind,
                PullRequest pullRequest, DeveloperRunView.Activity activity, RunSteps steps,
                DeveloperRunView.Technical technical) {

    /** The Jira key and summary from the task brief, and the repository and branch the run checked out. */
    record Task(String key, String summary, String repository, String branch) {
    }

    /** {@code url} is empty until the run opened a pull request. */
    record PullRequest(String url, boolean draft) {
    }

    /** What {@code reason} is: why the run stopped, the problem a pending step retry follows, or a question. */
    enum ReasonKind {
        STOP, RETRY_PENDING, RETRYING, QUESTION
    }

    boolean asked() {
        return reasonKind == ReasonKind.QUESTION && "NEEDS_DECISION".equals(productStatus);
    }
}
