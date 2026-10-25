package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationPolicyTest {
    private final DevRuntimeProperties runtime = new DevRuntimeProperties(
            Map.of("jest", List.of("yarn", "test"), "unit", List.of("mvn", "test")), null, 60, null, Map.of(),
            Map.of("jest", Toolchain.YARN));
    private final DevFactoryProperties properties = new DevFactoryProperties("https://example.org", new TreeMap<>(Map.of(
            "ui", new DevFactoryProperties.Repository("owner/ui", "master", "node:image", "jest", List.of("T"), List.of()),
            "svc", new DevFactoryProperties.Repository("owner/svc", "master", "maven:image", "unit", List.of("S"), List.of()))));
    private final VerificationPolicy policy = new VerificationPolicy(properties, runtime);

    private Candidate candidate(String repository) {
        return new Candidate(repository, "a".repeat(40), "b".repeat(40), Candidate.sha256(""), "", "CANDIDATE_UNVERIFIED");
    }

    private VerificationReceipt receipt(String repository, String plan, String image, List<String> argv,
                                        Toolchain toolchain, String lockfileSha) {
        return new VerificationReceipt("exec", repository, "a".repeat(40), "b".repeat(40), Candidate.sha256(""), plan,
                image, argv, "w", Instant.EPOCH, Instant.EPOCH, 0, 1, 3, 0, 0, "PASS", "ok", VerificationFailure.NONE,
                0, List.of(), List.of(), toolchain, lockfileSha);
    }

    @Test
    void frozenReceiptMatchesWithOrWithoutAPinnedLockfile() {
        var frozen = runtime.verificationCommand("jest", InstallMode.FROZEN);

        assertThat(policy.matches(candidate("ui"), receipt("ui", "jest", "node:image", frozen, Toolchain.YARN, null))).isTrue();
        assertThat(policy.matches(candidate("ui"), receipt("ui", "jest", "node:image", frozen, Toolchain.YARN, "d".repeat(64)))).isTrue();
    }

    @Test
    void resolvingReceiptMatchesOnlyWhenALockfileWasPinned() {
        var resolving = runtime.verificationCommand("jest", InstallMode.RESOLVE);

        assertThat(policy.matches(candidate("ui"), receipt("ui", "jest", "node:image", resolving, Toolchain.YARN, "d".repeat(64)))).isTrue();
        assertThat(policy.matches(candidate("ui"), receipt("ui", "jest", "node:image", resolving, Toolchain.YARN, null))).isFalse();
    }

    @Test
    void mavenReceiptStillMatchesWithoutALockfile() {
        assertThat(policy.matches(candidate("svc"),
                receipt("svc", "unit", "maven:image", List.of("mvn", "test"), Toolchain.MAVEN, null))).isTrue();
    }
}
