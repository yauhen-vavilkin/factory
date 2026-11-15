package org.folio.factory.devfactory.candidate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateTest {
    private static final String KEY = "sk-test-0123456789abcdef";

    @Test
    void namesTheFileOfTheFirstAddedLineThatHoldsTheKey() {
        var patch = """
                diff --git a/README.md b/README.md
                index 1111111..2222222 100644
                --- a/README.md
                +++ b/README.md
                @@ -1,2 +1,2 @@
                 context sk-test-0123456789abcdef
                -removed sk-test-0123456789abcdef
                +added without a key
                diff --git a/src/main/resources/application.yml b/src/main/resources/application.yml
                new file mode 100644
                index 0000000..3333333
                --- /dev/null
                +++ b/src/main/resources/application.yml
                @@ -0,0 +1 @@
                +++api-key: sk-test-0123456789abcdef
                """;

        assertThat(candidate(patch).findFileAdding(line -> line.contains(KEY)))
                .contains("src/main/resources/application.yml");
    }

    @Test
    void fileHeadersAndBinaryPatchesAreNotAddedLines() {
        var patch = """
                diff --git "a/docs/sk-test-0123456789abcdef.md" "b/docs/sk-test-0123456789abcdef.md"
                new file mode 100644
                --- /dev/null
                +++ "b/docs/sk-test-0123456789abcdef.md"
                @@ -0,0 +1 @@
                +plain text
                diff --git a/logo.png b/logo.png
                GIT binary patch
                literal 4
                +sk-test-0123456789abcdef
                """;

        assertThat(candidate(patch).findFileAdding(line -> line.contains(KEY))).isEmpty();
        assertThat(candidate(patch).findFileAdding(line -> line.contains("plain")))
                .contains("docs/sk-test-0123456789abcdef.md");
    }

    private static Candidate candidate(String patch) {
        return new Candidate("repo", "a".repeat(40), "b".repeat(40), Candidate.sha256(patch), patch,
                Candidate.UNVERIFIED);
    }
}
