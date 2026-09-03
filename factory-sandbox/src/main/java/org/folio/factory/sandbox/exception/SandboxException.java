package org.folio.factory.sandbox.exception;

public class SandboxException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SandboxException(String message) {
    super(message);
  }

  public SandboxException(String message, Throwable cause) {
    super(message, cause);
  }
}
