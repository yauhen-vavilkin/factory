package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationReceiptTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void receiptsStoredBeforeToolchainsExistedAreMavenReceipts() {
        var stored = (ObjectNode) json.readTree(json.writeValueAsString(receipt(null)));
        stored.remove("toolchain");

        var receipt = json.readValue(stored.toString(), VerificationReceipt.class);

        assertThat(receipt.toolchain()).isEqualTo(Toolchain.MAVEN);
        assertThat(receipt.result()).isEqualTo("PASS");
        assertThat(receipt.failureKind()).isEqualTo(VerificationFailure.NONE);
    }

    @Test
    void yarnReceiptsSurviveStorage() {
        var stored = json.writeValueAsString(receipt(Toolchain.YARN));

        assertThat(json.readValue(stored, VerificationReceipt.class).toolchain()).isEqualTo(Toolchain.YARN);
    }

    @Test
    void checksOfAYarnPlanWhoseInstallFailedSayYarnsErrorAndMavenChecksStayAsTheyWere() {
        var output = "yarn install v1.22.22\n[1/4] Resolving packages...\n"
                + "error Your lockfile needs to be updated, but yarn was run with `--frozen-lockfile`.\n"
                + "Trusted verification produced no fresh executed Jest tests";

        assertThat(notRun(Toolchain.YARN, output).failureReason()).isEqualTo("Checks could not run: the dependency"
                + " install of the agent's changes failed. Your lockfile needs to be updated, but yarn was run with"
                + " `--frozen-lockfile`.");
        assertThat(notRun(Toolchain.MAVEN, output).failureReason())
                .isEqualTo("Checks could not run: no test reports were written.");
        assertThat(notRun(Toolchain.YARN, "Done in 3s.\nerror Command \"test\" not found.").failureReason())
                .isEqualTo("Checks could not run: no test reports were written.");
    }

    /** Checks that wrote no test report, as CandidateVerifier records them. */
    private static VerificationReceipt notRun(Toolchain toolchain, String output) {
        return new VerificationReceipt("execution", "repo", "a".repeat(40), "b".repeat(40), "patch", "unit",
                "image", List.of("sh", "-c", "yarn install && yarn test"), "fresh", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1), 1, 0, null, null, null, "FAIL", output,
                VerificationFailure.INSUFFICIENT_EVIDENCE, 0, List.of(), List.of(), toolchain, null);
    }

    private static VerificationReceipt receipt(Toolchain toolchain) {
        return new VerificationReceipt("execution", "repo", "a".repeat(40), "b".repeat(40), "patch", "unit",
                "image", List.of("mvn", "test"), "fresh", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 0, 1, 1, 0, 0,
                "PASS", "ok", VerificationFailure.NONE, 0, List.of(), List.of(), toolchain, null);
    }
}
