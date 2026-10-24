package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.runtime.Toolchain;
import org.folio.factory.devfactory.runtime.YarnBaselineOutput;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative repair gate, separate from the independent verification verdict. */
public enum VerificationFailure {
    NONE, CANDIDATE, CANDIDATE_COMPILE, ENVIRONMENT, INVALID_EVIDENCE, INSUFFICIENT_EVIDENCE, UNKNOWN;

    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    private static final List<String> INFRASTRUCTURE_MARKERS = List.of("could not find a valid docker environment",
            "cannot connect to the docker daemon", "no space left on device", "command exceeded", "docker: error",
            "unable to pull");
    private static final List<String> NETWORK_MARKERS = List.of("connection refused", "connection timed out",
            "read timed out", "unknownhostexception", "could not resolve dependencies", "could not transfer artifact",
            "network is unreachable", "accessdeniedexception");

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
        boolean environment = INFRASTRUCTURE_MARKERS.stream().anyMatch(text::contains) || switch (toolchain) {
            case MAVEN -> NETWORK_MARKERS.stream().anyMatch(text::contains);
            // Jest output can legitimately mention network errors, so only yarn's own error lines count.
            case YARN -> YarnBaselineOutput.isTransientDownloadFailure(diagnostics)
                    || text.lines().map(line -> ANSI.matcher(line).replaceAll(""))
                    .filter(line -> line.startsWith("error "))
                    .anyMatch(line -> NETWORK_MARKERS.stream().anyMatch(line::contains));
        };
        if (environment) return ENVIRONMENT;
        boolean compileError = switch (toolchain) {
            case MAVEN -> text.contains("[error] compilation error")
                    && Pattern.compile("(?m).*\\.java:\\[[0-9]+,[0-9]+].*").matcher(text).find();
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
