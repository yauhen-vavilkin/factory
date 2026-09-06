package org.folio.factory.sandbox.harness;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelReplyTest {

  /**
   * T23 R4: the no-arg single-call accessor that silently returned the first
   * call of a batch is removed; callers must iterate {@code toolCalls()} and
   * handle every call (the audit on 89bbe1f found test-only callers).
   */
  @Test
  void singleCallAccessorIsRemoved() {
    assertThrows(NoSuchMethodException.class,
        () -> ModelReply.class.getDeclaredMethod("toolCall"));
  }
}
