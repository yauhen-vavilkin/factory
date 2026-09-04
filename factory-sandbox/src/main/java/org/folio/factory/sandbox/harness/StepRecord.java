package org.folio.factory.sandbox.harness;

public record StepRecord(String ts, int stepNumber, String toolName, int argsLength, int outputLength,
    boolean ok, long durationMs) {
}
