package org.folio.factory.devfactory.verification;

import java.util.Locale;

/** Conservative repair gate, separate from the independent verification verdict. */
public enum VerificationFailure {
    NONE, CANDIDATE, CANDIDATE_COMPILE, ENVIRONMENT, INVALID_EVIDENCE, INSUFFICIENT_EVIDENCE, UNKNOWN;

    public boolean repairable() { return this == CANDIDATE; }

    static VerificationFailure classify(String result, String diagnostics, boolean invalid,
                                        boolean insufficient, Integer failures, Integer errors) {
        if ("PASS".equals(result)) return NONE;
        if (invalid) return INVALID_EVIDENCE;
        String text = diagnostics.toLowerCase(Locale.ROOT);
        if (java.util.List.of("could not find a valid docker environment", "cannot connect to the docker daemon",
                "connection refused", "connection timed out", "read timed out", "unknownhostexception",
                "could not resolve dependencies", "could not transfer artifact", "no space left on device",
                "command exceeded", "docker: error", "unable to pull", "network is unreachable",
                "accessdeniedexception")
                .stream().anyMatch(text::contains)) return ENVIRONMENT;
        if (text.contains("[error] compilation error")
                && java.util.regex.Pattern.compile("(?m).*\\.java:\\[[0-9]+,[0-9]+].*").matcher(text).find())
            return CANDIDATE_COMPILE;
        if (insufficient) return INSUFFICIENT_EVIDENCE;
        // Exceptions may be infrastructure failures. Only actual assertion failures are eligible.
        if (failures != null && failures > 0 && errors != null && errors == 0
                && (text.contains("assertionfailederror") || text.contains("assertionerror")
                    || text.contains("expected:") && text.contains("but was:"))) return CANDIDATE;
        return UNKNOWN;
    }
}
