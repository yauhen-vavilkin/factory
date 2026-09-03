package org.folio.factory.sandbox.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShellTest {

  @Test
  void quotesPlainValue() {
    assertEquals("'repo/pom.xml'", Shell.quote("repo/pom.xml"));
  }

  @Test
  void quotesValueWithSingleQuote() {
    assertEquals("'it'\\''s'", Shell.quote("it's"));
  }

  @Test
  void quotesEmptyValue() {
    assertEquals("''", Shell.quote(""));
  }
}
