package org.folio.factory.devfactory.candidate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import static org.assertj.core.api.Assertions.*;

class CandidateFreezerTest {
    @TempDir Path source;
    @Test void roundTripPreservesDeletionBinaryAdditionAndExecutableModeIgnoringUntrustedGit() throws Exception {
        CandidateFreezer.git(source, "init", "--quiet");
        Files.writeString(source.resolve("deleted.txt"), "delete me\n");
        Files.writeString(source.resolve("run.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(source.resolve(".gitignore"), "ignored.secret\n");
        Files.createDirectories(source.resolve("src/target"));
        Files.writeString(source.resolve("src/target/Tracked.java"), "class Tracked {}\n");
        CandidateFreezer.git(source, "add", ".");
        CandidateFreezer.git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.org", "commit", "-qm", "base");
        String base = CandidateFreezer.git(source, "rev-parse", "HEAD").strip();
        Files.delete(source.resolve("deleted.txt"));
        Files.write(source.resolve("binary.dat"), new byte[]{0, 1, 2, (byte)255});
        Files.setPosixFilePermissions(source.resolve("run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(source.resolve("src/target/Tracked.java"), "class Tracked { int changed; }\n");
        Files.createDirectories(source.resolve("target"));
        Files.writeString(source.resolve("target/noise"), "exclude build output");
        Files.writeString(source.resolve("ignored.secret"), "must not be staged");
        var freezer = new CandidateFreezer();
        Candidate candidate = freezer.freeze("fixture", source.toString(), base, source);
        assertThat(candidate.patch()).contains("GIT binary patch", "deleted file mode", "new mode 100755")
                .doesNotContain("target/noise", "ignored.secret");
        Path reconstructed = freezer.reconstruct(source.toString(), candidate);
        try {
            assertThat(Files.exists(reconstructed.resolve("deleted.txt"))).isFalse();
            assertThat(Files.readAllBytes(reconstructed.resolve("binary.dat"))).containsExactly(0, 1, 2, (byte)255);
            assertThat(Files.isExecutable(reconstructed.resolve("run.sh"))).isTrue();
            assertThat(Files.readString(reconstructed.resolve("src/target/Tracked.java"))).contains("changed");
            assertThat(CandidateFreezer.git(reconstructed, "write-tree").strip()).isEqualTo(candidate.treeSha());
        } finally { CandidateFreezer.delete(reconstructed); }
        assertThatThrownBy(() -> new Candidate("fixture", base, candidate.treeSha(), "bad", candidate.patch(), "CANDIDATE_UNVERIFIED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsTreeEqualToBaseEvenWhenIgnoredResidueExists() throws Exception {
        CandidateFreezer.git(source, "init", "--quiet");
        Files.writeString(source.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(source.resolve("code.txt"), "unchanged\n");
        CandidateFreezer.git(source, "add", ".");
        CandidateFreezer.git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.org", "commit", "-qm", "base");
        String base = CandidateFreezer.git(source, "rev-parse", "HEAD").strip();
        Files.writeString(source.resolve("ignored.txt"), "generated residue");

        assertThatThrownBy(() -> new CandidateFreezer().freeze("fixture", source.toString(), base, source))
                .hasMessageContaining("no changes");
    }
}
