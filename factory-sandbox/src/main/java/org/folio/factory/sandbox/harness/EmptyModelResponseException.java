package org.folio.factory.sandbox.harness;

/**
 * T23 R1: the model returned an empty response (zero generations or no
 * assistant output). Thrown by {@code SpringAiChatModelAdapter} instead of
 * crashing unguarded, so the harness can record a preserved diagnostic
 * outcome distinct from a provider failure ({@code MODEL_ERROR}).
 */
public class EmptyModelResponseException extends RuntimeException {

  public EmptyModelResponseException(String message) {
    super(message);
  }
}
