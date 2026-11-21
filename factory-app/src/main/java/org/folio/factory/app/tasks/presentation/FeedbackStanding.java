package org.folio.factory.app.tasks.presentation;

/**
 * Where the PR feedback of a task's pull request stands, as its run knows it: nothing to say, a question or new
 * PR comments wait for the developer, a round works now, or the pull request is merged or closed without merge.
 * My tasks and the task page read it so that they say the same as the run page.
 */
public enum FeedbackStanding {
    NONE, WAITS_FOR_YOU, WORKING, MERGED, CLOSED
}
