package org.folio.factory.devfactory.resolution;

/** Transient or environmental repository lookup failure; admission remains retryable. */
public final class RepositoryAccessException extends RuntimeException {
  public RepositoryAccessException(String message) {
    super(message);
  }

  public RepositoryAccessException(String message, Throwable cause) {
    super(message, cause);
  }
}
