package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.runtime.Toolchain;

import java.util.List;
import java.util.Locale;

/** Conservative repair gate, separate from the independent verification verdict. */
public enum VerificationFailure {
    NONE, CANDIDATE, CANDIDATE_COMPILE, ENVIRONMENT, INVALID_EVIDENCE, INSUFFICIENT_EVIDENCE, UNKNOWN;

    private static final List<String> ENVIRONMENT_MARKERS = List.of("could not find a valid docker environment",
            "cannot connect to the docker daemon", "connection refused", "connection timed out", "read timed out",
            "unknownhostexception", "could not resolve dependencies", "could not transfer artifact",
            "no space left on device", "command exceeded", "docker: error", "unable to pull",
            "network is unreachable", "accessdeniedexception");
    private static final List<String> YARN_ENVIRONMENT_MARKERS = List.of("etimedout", "econnreset", "eai_again",
            "enotfound", "socket hang up", "trouble with your network connection");

    public boolean repairable() { return this == CANDIDATE; }

    static VerificationFailure classify(String result, String diagnostics, boolean invalid,
                                        boolean insufficient, Integer failures, Integer errors) {
        return classify(Toolchain.MAVEN, result, diagnostics, invalid, insufficient, failures, errors);
    }

    static VerificationFailure classify(Toolchain toolchain, String result, String diagnostics, boolean invalid,
                                        boolean insufficient, Integer failures, Integer errors) {
        if ("PASS".equals(result)) return NONE;
        if (invalid) return INVALID_EVIDENCE;
        String text = diagnostics.toLowerCase(Locale.ROOT);
        boolean environment = ENVIRONMENT_MARKERS.stream().anyMatch(text::contains) || switch (toolchain) {
            case MAVEN -> false;
            case YARN -> YARN_ENVIRONMENT_MARKERS.stream().anyMatch(text::contains);
        };
        if (environment) return ENVIRONMENT;
        boolean compileError = switch (toolchain) {
            case MAVEN -> text.contains("[error] compilation error")
                    && java.util.regex.Pattern.compile("(?m).*\\.java:\\[[0-9]+,[0-9]+].*").matcher(text).find();
            case YARN -> false;
        };
        if (compileError) return CANDIDATE_COMPILE;
        if (insufficient) return INSUFFICIENT_EVIDENCE;
        // Exceptions may be infrastructure failures. Only actual assertion failures are eligible.
        boolean assertion = switch (toolchain) {
            case MAVEN -> text.contains("assertionfailederror") || text.contains("assertionerror")
                    || text.contains("expected:") && text.contains("but was:");
            case YARN -> text.contains("expect(received)") || text.contains("assertionerror")
                    || text.contains("expected:") && text.contains("received:");
        };
        if (failures != null && failures > 0 && errors != null && errors == 0 && assertion) return CANDIDATE;
        return UNKNOWN;
    }
}
