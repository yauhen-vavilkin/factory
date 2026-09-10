package org.folio.factory.devfactory.profile;

public final class UnsupportedProfileException extends RuntimeException {
  private final String code;

  public UnsupportedProfileException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
