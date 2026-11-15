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
    void aResultWithItsOwnReasonKeepsItAndAPassedResultHasNone() {
        assertThat(StopReasons.ofResult(read("{\"state\":\"DEVELOPMENT_FAILED\",\"reason\":\"No candidate\"}")))
                .isEqualTo("DEVELOPMENT_FAILED: No candidate");
        assertThat(StopReasons.ofResult(read("{\"state\":\"VERIFIED\",\"testCount\":2}"))).isEmpty();
        assertThat(StopReasons.ofResult(JSON.createObjectNode())).isEmpty();
    }

    private static JsonNode read(String json) {
        return JSON.readTree(json);
    }
}
