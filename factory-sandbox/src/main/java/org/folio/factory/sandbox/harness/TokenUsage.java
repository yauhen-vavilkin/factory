package org.folio.factory.sandbox.harness;

public record TokenUsage(long promptTokens, long completionTokens) {

  public static final TokenUsage ZERO = new TokenUsage(0, 0);
}
