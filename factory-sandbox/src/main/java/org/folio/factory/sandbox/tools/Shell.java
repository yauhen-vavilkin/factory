package org.folio.factory.sandbox.tools;

public final class Shell {

  private Shell() {
  }

  public static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
