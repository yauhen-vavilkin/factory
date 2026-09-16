package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CandidateDeliveryTest {
    @TempDir Path root;
    Path source;
    Path remote;
    Candidate candidate;
    CandidateDelivery delivery;
    DeliveryTarget target = new DeliveryTarget("fixture/fork", "master", true);

    @BeforeEach void setup() throws Exception {
        source = Files.createDirectory(root.resolve("source"));
        remote = root.resolve("fixture/fork.git");
        Files.createDirectories(remote.getParent());
        CandidateFreezer.git(source, "init", "--quiet", "--initial-branch=master");
        Files.writeString(source.resolve("old.txt"), "before\n");
        CandidateFreezer.git(source, "add", ".");
        CandidateFreezer.git(source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.org", "commit", "-qm", "base");
        String base = CandidateFreezer.git(source, "rev-parse", "HEAD").strip();
        CandidateFreezer.git(root, "clone", "--quiet", "--bare", source.toString(), remote.toString());
        Files.delete(source.resolve("old.txt"));
        Files.write(source.resolve("binary.dat"), new byte[]{0, 1, (byte)255});
        var freezer = new CandidateFreezer();
        candidate = freezer.freeze("fixture", source.toString(), base, source);
        delivery = new CandidateDelivery(freezer, root + "/");
    }

    private VerificationReceipt receipt(String execution, int exit) {
        return new VerificationReceipt(execution, candidate.repository(), candidate.baseSha(), candidate.treeSha(), candidate.patchSha256(),
                "focused", "fixture", List.of("true"), "fixture", Instant.EPOCH, Instant.EPOCH, exit, exit == 0 ? "PASS" : "FAIL", "");
    }

    private DeliveryReceipt deliver(VerificationReceipt receipt, DeliveryTarget destination, String token) {
        return delivery.deliver("execution", "MODSIDECAR-196", "Fixture", source.toString(), candidate, receipt, destination, token);
    }

    @Test void preservesExactTreeParentAndReusesDeterministicCommit() {
        var first = deliver(receipt("execution", 0), target, "fixture-token");
        var second = deliver(receipt("execution", 0), target, "fixture-token");
        assertThat(first).isEqualTo(second);
        assertThat(CandidateFreezer.git(remote, "rev-parse", first.branch() + "^{tree}").strip()).isEqualTo(candidate.treeSha());
        assertThat(CandidateFreezer.git(remote, "rev-parse", first.branch() + "^").strip()).isEqualTo(candidate.baseSha());
    }

    @Test void rejectsFailedWrongOrMissingReceiptAndMissingTokenBeforeRemoteWrites() {
        assertThatThrownBy(() -> deliver(receipt("execution", 1), target, "token")).hasMessageContaining("matching successful");
        assertThatThrownBy(() -> deliver(receipt("other", 0), target, "token")).hasMessageContaining("matching successful");
        assertThatThrownBy(() -> deliver(null, target, "token")).hasMessageContaining("receipt");
        assertThatThrownBy(() -> deliver(receipt("execution", 0), target, "")).hasMessageContaining("FACTORY_CONNECTORS_GITHUB_TOKEN");
        assertThat(CandidateFreezer.git(remote, "branch", "--list", "factory/*")).isBlank();
    }

    @Test void refusesUpstreamUnauthorizedAndInvalidBranch() {
        assertThatThrownBy(() -> deliver(receipt("execution", 0), new DeliveryTarget("folio-org/module", "master", true), "token"))
                .hasMessageContaining("user-owned");
        assertThatThrownBy(() -> deliver(receipt("execution", 0), new DeliveryTarget("fixture/fork", "master", false), "token"))
                .hasMessageContaining("authorization");
        assertThatThrownBy(() -> deliver(receipt("execution", 0), new DeliveryTarget("fixture/fork", "bad..branch", true), "token"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CandidateFreezer.git(remote, "branch", "--list", "factory/*")).isBlank();
    }

    @Test void blocksConflictWithoutChangingRemoteBranch() {
        String branch = "factory/modsidecar-196-" + candidate.patchSha256().substring(0, 8);
        CandidateFreezer.git(remote, "update-ref", "refs/heads/" + branch, candidate.baseSha());
        assertThatThrownBy(() -> deliver(receipt("execution", 0), target, "token")).hasMessageContaining("conflicts");
        assertThat(CandidateFreezer.git(remote, "rev-parse", branch).strip()).isEqualTo(candidate.baseSha());
    }

    @Test void blocksIncompatibleForkBaseBeforePush() {
        String unrelated = CandidateFreezer.git(remote, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.org",
                "commit-tree", candidate.baseSha() + "^{tree}", "-m", "unrelated root").strip();
        CandidateFreezer.git(remote, "update-ref", "refs/heads/master", unrelated);
        assertThatThrownBy(() -> deliver(receipt("execution", 0), target, "token")).hasMessageContaining("merge-base failed");
        assertThat(CandidateFreezer.git(remote, "branch", "--list", "factory/*")).isBlank();
    }
}
