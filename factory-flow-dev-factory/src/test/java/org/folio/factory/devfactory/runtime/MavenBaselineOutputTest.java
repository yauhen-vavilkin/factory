package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MavenBaselineOutputTest {
    @Test
    void emitsOnlyUsefulBoundedSanitizedActivity() {
        var events = new ArrayList<String>();
        var output = new MavenBaselineOutput(events::add);
        output.accept("[INFO] ordinary noise");
        output.accept("[INFO] --- compiler:3.16.0:compile (default-compile) @ module ---");
        output.accept("[INFO] Downloading from central: https://alice:secret@repo1.maven.org/file");
        output.accept("[ERROR] token=top-secret");
        for (int i = 0; i < 1000; i++) output.accept("[INFO] Downloading from central: artifact-" + i);
        for (int i = 0; i < 100; i++) output.accept("[WARNING] warning " + i);
        for (int i = 0; i < 100; i++)
            output.accept("[INFO] --- compiler:3.16.0:compile (compile-" + i + ") @ module ---");
        output.accept("[INFO] BUILD SUCCESS");
        output.accept("[INFO] BUILD FAILURE");

        assertThat(events).hasSize(MavenBaselineOutput.EVENT_LIMIT);
        assertThat(events).noneMatch(line -> line.contains("ordinary noise") || line.contains("top-secret")
                || line.contains("alice:secret"));
        assertThat(events).allMatch(line -> line.length() <= 240);
        assertThat(MavenBaselineOutput.sanitize("first\nhttps://user:pass@example.org/file\ntoken=secret"))
                .isEqualTo("first\nhttps://[REDACTED]@example.org/file\ntoken=[REDACTED]");
    }

    @Test
    void dependencyFloodCannotHideLaterBuildActivity() {
        var events = new ArrayList<String>();
        var output = new MavenBaselineOutput(events::add);
        for (int i = 0; i < 1000; i++) output.accept("[INFO] Downloaded from central: artifact-" + i);
        output.accept("[INFO] Compiling 42 source files");
        output.accept("[INFO] Running ExampleTest");
        output.accept("[INFO] Tests run: 7, Failures: 0");
        output.accept("[INFO] BUILD FAILURE");

        assertThat(events).anyMatch(line -> line.contains("Compiling 42"));
        assertThat(events).anyMatch(line -> line.contains("Running ExampleTest"));
        assertThat(events).anyMatch(line -> line.contains("Tests run: 7"));
        assertThat(events).anyMatch(line -> line.contains("BUILD FAILURE"));
        assertThat(events).hasSizeLessThanOrEqualTo(MavenBaselineOutput.EVENT_LIMIT);
    }

    @Test
    void heartbeatsShareTheBoundedProgressBudget() {
        var events = new ArrayList<String>();
        var output = new MavenBaselineOutput(events::add);

        for (int i = 0; i < 1000; i++) output.heartbeat();

        assertThat(events).hasSize(MavenBaselineOutput.EVENT_LIMIT)
                .allMatch("Maven baseline is still running"::equals);
    }

    @Test
    void extractsObservedIncompleteCentralTransfer() {
        String failure = "[ERROR] Could not transfer artifact net.sf.saxon:Saxon-HE:jar:12.9 "
                + "from/to central (https://repo.maven.apache.org/maven2): "
                + "Premature end of Content-Length delimited message body";

        assertThat(MavenBaselineOutput.failureSummary(failure)).contains(
                "Dependency resolution failed: could not transfer Saxon-HE 12.9 from Maven Central (incomplete download)");
    }
}
