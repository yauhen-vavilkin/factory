package org.folio.factory.devfactory.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitRefsTest {

    @Test
    void validNamesPass() {
        assertThat(GitRefs.isValidBranchName("main")).isTrue();
        assertThat(GitRefs.isValidBranchName("feature/x-y")).isTrue();
        assertThat(GitRefs.isValidBranchName("task/TASK-001")).isTrue();
        assertThat(GitRefs.isValidBranchName("release/1.0")).isTrue();

        assertThat(GitRefs.isRawCommitId("main")).isFalse();
        assertThat(GitRefs.isRawCommitId("feature/x-y")).isFalse();
        assertThat(GitRefs.isRawCommitId("task/TASK-001")).isFalse();
        assertThat(GitRefs.isRawCommitId("release/1.0")).isFalse();
    }

    @Test
    void invalidRefnamesRejected() {
        // null / blank
        assertThat(GitRefs.isValidBranchName(null)).isFalse();
        assertThat(GitRefs.isValidBranchName("")).isFalse();
        assertThat(GitRefs.isValidBranchName("   ")).isFalse();
        // exactly '@'
        assertThat(GitRefs.isValidBranchName("@")).isFalse();
        // leading '.'
        assertThat(GitRefs.isValidBranchName(".main")).isFalse();
        // leading '-'
        assertThat(GitRefs.isValidBranchName("-main")).isFalse();
        // trailing '/'
        assertThat(GitRefs.isValidBranchName("feature/")).isFalse();
        // trailing '.'
        assertThat(GitRefs.isValidBranchName("feature.")).isFalse();
        // empty path component
        assertThat(GitRefs.isValidBranchName("a//b")).isFalse();
        // component starting with '.'
        assertThat(GitRefs.isValidBranchName("a/.b")).isFalse();
        // component ending with '.lock'
        assertThat(GitRefs.isValidBranchName("x/branch.lock")).isFalse();
        // '..'
        assertThat(GitRefs.isValidBranchName("a..b")).isFalse();
        // '@{'
        assertThat(GitRefs.isValidBranchName("task@{1}")).isFalse();
        // space
        assertThat(GitRefs.isValidBranchName("a b")).isFalse();
        // ASCII control char (tab)
        assertThat(GitRefs.isValidBranchName("a\tb")).isFalse();
        // '~'
        assertThat(GitRefs.isValidBranchName("a~b")).isFalse();
        // '^'
        assertThat(GitRefs.isValidBranchName("a^b")).isFalse();
        // ':'
        assertThat(GitRefs.isValidBranchName("a:b")).isFalse();
        // '?'
        assertThat(GitRefs.isValidBranchName("a?b")).isFalse();
        // '['
        assertThat(GitRefs.isValidBranchName("a[b")).isFalse();
        // '\'
        assertThat(GitRefs.isValidBranchName("a\\b")).isFalse();
    }

    @Test
    void rawCommitIdsDetected() {
        assertThat(GitRefs.isRawCommitId("0123456789abcdef0123456789abcdef01234567")).isTrue();
        assertThat(GitRefs
            .isRawCommitId("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")).isTrue();

        assertThat(GitRefs.isRawCommitId("z".repeat(40))).isFalse();
    }
}
