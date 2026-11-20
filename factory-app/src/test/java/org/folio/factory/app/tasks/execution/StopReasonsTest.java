package org.folio.factory.app.tasks.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StopReasonsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            UNKNOWN               | 300 | 2 | 0 | 1  | VERIFICATION_FAILED: 2 of 300 tests failed
            CANDIDATE             | 300 | 1 | 1 | 1  | VERIFICATION_FAILED: 2 of 300 tests failed
            ENVIRONMENT           | 1   | 1 | 0 | 1  | VERIFICATION_FAILED: 1 of 1 test failed
            CANDIDATE_COMPILE     |     |   |   | 1  | VERIFICATION_FAILED: the code does not compile
            ENVIRONMENT           |     |   |   | -1 | VERIFICATION_NOT_RUN: the build environment failed (Docker, network, disk space or time limit)
            INVALID_EVIDENCE      |     |   |   | 1  | VERIFICATION_NOT_RUN: the test reports could not be read
            INSUFFICIENT_EVIDENCE |     |   |   | 0  | VERIFICATION_NOT_RUN: no test reports were written
            UNKNOWN               | 300 | 0 | 0 | 1  | VERIFICATION_FAILED: the build failed with exit code 1
            """)
    void failedChecksGetTheirCauseFromTheCountsThenTheKind(String kind, Integer tests, Integer failures,
                                                           Integer errors, int exitCode, String reason) {
        var result = JSON.createObjectNode().put("state", "VERIFICATION_FAILED").put("exitCode", exitCode)
                .put("failureKind", kind);
        if (tests != null) {
            result.put("testCount", tests).put("failureCount", failures).put("errorCount", errors);
        }

        assertThat(StopReasons.ofResult(result)).isEqualTo(reason);
    }

    @Test
    void checksOfAYarnPlanWhoseInstallFailedOnTheAgentsChangesNameTheInstallAndYarnsError() {
        var result = read("{\"state\":\"VERIFICATION_FAILED\",\"exitCode\":1,"
                + "\"failureKind\":\"INSUFFICIENT_EVIDENCE\",\"testCount\":null}");
        var output = "yarn install v1.22.22\\n[1/4] Resolving packages...\\nerror Your lockfile needs to be"
                + " updated, but yarn was run with `--frozen-lockfile`.\\nTrusted verification produced no fresh"
                + " executed Jest tests";

        assertThat(StopReasons.ofResult(result, receipt("YARN", output)))
                .isEqualTo("VERIFICATION_NOT_RUN: the dependency install of the agent's changes failed. Your"
                        + " lockfile needs to be updated, but yarn was run with `--frozen-lockfile`. Start a new run"
                        + " and tell the agent to update yarn.lock with every change to package.json and to use"
                        + " only packages that the npm registry has.");
        // Maven checks, an install that finished, and a run without a receipt keep today's reason.
        assertThat(StopReasons.ofResult(result, receipt("MAVEN", output)))
                .isEqualTo("VERIFICATION_NOT_RUN: no test reports were written");
        assertThat(StopReasons.ofResult(result, receipt("YARN", "Done in 3s.\\nerror Command \\\"test\\\" not found.")))
                .isEqualTo("VERIFICATION_NOT_RUN: no test reports were written");
        assertThat(StopReasons.ofResult(result, read("{\"state\":\"NOT_RUN\"}")))
                .isEqualTo("VERIFICATION_NOT_RUN: no test reports were written");
        assertThat(StopReasons.ofResult(result)).isEqualTo("VERIFICATION_NOT_RUN: no test reports were written");
    }

    @Test
    void aResultWithItsOwnReasonKeepsItAndAPassedResultHasNone() {
        assertThat(StopReasons.ofResult(read("{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"No candidate\"}")))
                .isEqualTo("DEVELOPMENT_FAILED: No candidate");
        assertThat(StopReasons.ofResult(read("{\"state\":\"VERIFIED\",\"testCount\":2}"))).isEmpty();
        assertThat(StopReasons.ofResult(JSON.createObjectNode())).isEmpty();
    }

    @Test
    void anEngineThatGaveUpNamesTheStepAsItsCardDoesTheTriesAndTheFirstLineOfTheError() {
        assertThat(StopReasons.engineGaveUp("publish", 3, "429 Too Many Requests: rate limit.\nat line 2"))
                .isEqualTo("Factory could not finish the step \"Create pull request\" after 3 tries: 429 Too Many"
                        + " Requests: rate limit. Start a new run.");
        // The JSON body an HTTP client quotes after the status reads as its message.
        assertThat(StopReasons.engineGaveUp("publish", 3,
                "429 Too Many Requests: \"{\"message\": \"API rate limit exceeded\"}\""))
                .isEqualTo("Factory could not finish the step \"Create pull request\" after 3 tries: 429 Too Many"
                        + " Requests: API rate limit exceeded. Start a new run.");
        assertThat(StopReasons.engineGaveUp("publish", 3, "502 Bad Gateway: \"{\"error\": 7}\""))
                .contains("after 3 tries: 502 Bad Gateway: \"{\"error\": 7}\". Start");
        // The message is read first, then cut to its first line and cleared of credentials like any other error.
        assertThat(StopReasons.engineGaveUp("publish", 3, "500 Server Error: \"{\"message\": \"fetch"
                + " https:\\/\\/bot:hunter2@example.org\\/x failed\\nsecond line\"}\""))
                .contains("after 3 tries: 500 Server Error: fetch https://[REDACTED]@example.org/x failed. Start")
                .doesNotContain("hunter2", "second line");
        // A step that no card of the run page covers is named by its id in words.
        assertThat(StopReasons.engineGaveUp("initialize-pr-feedback", 2, "boom"))
                .startsWith("Factory could not finish the step \"Initialize PR feedback\" after 2 tries: boom.");
    }

    @Test
    void theErrorOfAnEngineThatGaveUpHasNoCredentialsIdsOrBareExceptionName() {
        assertThat(StopReasons.engineGaveUp("verify", 3, "push to https://user:secret@github.com failed for"
                + " factory/0c5e0a3e-5a2b-4c1d-9e8f-1234567890ab with Authorization: Bearer abc.def"))
                .doesNotContain("secret", "0c5e0a3e", "abc.def")
                .startsWith("Factory could not finish the step \"Verify changes\" after 3 tries: push to https://");
        assertThat(StopReasons.engineGaveUp("publish", 3, "NullPointerException"))
                .isEqualTo("Factory could not finish the step \"Create pull request\" after 3 tries: an unexpected"
                        + " error. Start a new run.");
        assertThat(StopReasons.engineGaveUp("publish", 3, "x".repeat(500))).hasSizeLessThan(400);
    }

    /** The parts of dev_verification.json that say which toolchain ran and what it printed. */
    private static JsonNode receipt(String toolchain, String output) {
        return read("{\"result\":\"FAIL\",\"toolchain\":\"" + toolchain + "\",\"output\":\"" + output + "\"}");
    }

    private static JsonNode read(String json) {
        return JSON.readTree(json);
    }
}
