package org.folio.factory.sandbox.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.folio.factory.sandbox.exception.SandboxException;
import org.junit.jupiter.api.Test;

class SandboxContractsTest {

  @Test
  void sandboxSpecHoldsComponents() {
    SandboxSpec spec = new SandboxSpec("task-1", "https://example.com/repo.git", "main", "feature/x");
    assertEquals("task-1", spec.taskId());
    assertEquals("https://example.com/repo.git", spec.repoUrl());
    assertEquals("main", spec.baseBranch());
    assertEquals("feature/x", spec.branch());
  }

  @Test
  void sandboxHandleHoldsComponents() {
    SandboxHandle handle = new SandboxHandle("sb-123", "c-456");
    assertEquals("sb-123", handle.sandboxId());
    assertEquals("c-456", handle.containerId());
  }

  @Test
  void commandResultHoldsComponents() {
    CommandResult result = new CommandResult(0, "out", "err", 100L);
    assertEquals(0, result.exitCode());
    assertEquals("out", result.stdout());
    assertEquals("err", result.stderr());
    assertEquals(100L, result.durationMs());
  }

  @Test
  void commandResultOkWhenExitCodeZero() {
    assertTrue(new CommandResult(0, "out", "err", 100L).ok());
  }

  @Test
  void commandResultNotOkWhenExitCodeNonZero() {
    assertFalse(new CommandResult(1, "", "boom", 5L).ok());
  }

  @Test
  void sandboxExceptionStoresMessage() {
    SandboxException exception = new SandboxException("x");
    assertEquals("x", exception.getMessage());
    assertNull(exception.getCause());
  }

  @Test
  void sandboxExceptionStoresCause() {
    Throwable cause = new IllegalStateException("root");
    SandboxException exception = new SandboxException("y", cause);
    assertEquals("y", exception.getMessage());
    assertSame(cause, exception.getCause());
  }
}
