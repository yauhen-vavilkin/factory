package org.folio.factory.app.tasks.execution;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.folio.factory.app.web.UiFormat;
import org.folio.factory.devfactory.runtime.MavenBaselineOutput;
import org.folio.factory.devfactory.verification.VerificationFailure;
import tools.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * The stored reason of a run that ended without one of its own, built from what its steps left. The text is
 * {@code STAGE: cause}, like the reasons the workers store; {@code AttemptFailure} says it in page words.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class StopReasons {

    /** The stage of a reason whose checks ran and failed. */
    public static final String CHECKS_FAILED = "VERIFICATION_FAILED";
    /** The stage of a reason whose checks could not run to a result. */
    public static final String CHECKS_NOT_RUN = "VERIFICATION_NOT_RUN";
    /** The start of {@link #engineGaveUp}. */
    public static final String ENGINE_GAVE_UP_START = "Factory could not finish the step ";
    private static final int MAX_ERROR = 300;
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** What the engine stores for an exception without a message: its simple class name. */
    private static final Pattern BARE_EXCEPTION = Pattern.compile("[A-Z][A-Za-z0-9$]*(?:Exception|Error)");

    /**
     * The reason of the latest verification result: its own reason when it has one, the cause of failed checks
     * from their counts and kind, or blank for a result that says nothing (also a passed one).
     */
    public static String ofResult(JsonNode result) {
        var state = result.path("state").asString("");
        var reason = result.path("reason").asString("");
        if (!reason.isBlank()) {
            return state + ": " + reason;
        }
        return CHECKS_FAILED.equals(state) ? checks(result) : "";
    }

    /**
     * The reason of a run whose engine gave up on step {@code stepId} after {@code tries} failed tries, with the step
     * named as its card on the run page; the error the engine stored is cut to its first line, without credentials,
     * ids or a bare exception name.
     */
    public static String engineGaveUp(String stepId, int tries, String error) {
        return ENGINE_GAVE_UP_START + "\"" + UiFormat.stepInWords(stepId) + "\" after " + tries
                + " tries: " + oneLine(error) + ". Start a new attempt.";
    }

    private static String oneLine(String error) {
        var line = MavenBaselineOutput.redactCredentials(error.strip().lines().findFirst().orElse(""));
        line = UUID.matcher(line).replaceAll("…").strip();
        if (line.isEmpty() || BARE_EXCEPTION.matcher(line).matches()) {
            return "an unexpected error";
        }
        line = line.length() > MAX_ERROR ? line.substring(0, MAX_ERROR) + "…" : line;
        return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
    }

    /** Counts first: tests that failed are the cause even when the build environment looks broken too. */
    private static String checks(JsonNode result) {
        var kind = failureKind(result.path("failureKind").asString(""));
        var tests = result.path("testCount").asInt(0);
        var failed = result.path("failureCount").asInt(0) + result.path("errorCount").asInt(0);
        if (kind == VerificationFailure.CANDIDATE_COMPILE) {
            return CHECKS_FAILED + ": the code does not compile";
        }
        if (tests > 0 && failed > 0) {
            return CHECKS_FAILED + ": " + failed + " of " + tests + (tests == 1 ? " test" : " tests") + " failed";
        }
        return switch (kind) {
            case ENVIRONMENT -> CHECKS_NOT_RUN
                    + ": the build environment failed (Docker, network, disk space or time limit)";
            case INVALID_EVIDENCE -> CHECKS_NOT_RUN + ": the test reports could not be read";
            case INSUFFICIENT_EVIDENCE -> CHECKS_NOT_RUN + ": no test reports were written";
            default -> CHECKS_FAILED + ": the build failed with exit code " + result.path("exitCode").asInt();
        };
    }

    private static VerificationFailure failureKind(String name) {
        try {
            return VerificationFailure.valueOf(name);
        } catch (IllegalArgumentException e) {
            return VerificationFailure.UNKNOWN;
        }
    }
}
