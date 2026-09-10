package org.folio.factory.devfactory.resolution;

public final class RepositorySecurityException extends IllegalArgumentException {
  public RepositorySecurityException(String message) {
    super(message);
  }

  public RepositorySecurityException(String message, Throwable cause) {
    super(message, cause);
  }
}
