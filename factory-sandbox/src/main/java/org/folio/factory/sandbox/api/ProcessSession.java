package org.folio.factory.sandbox.api;

import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/** Full-duplex boundary for a long-lived stdin/stdout/stderr process. */
public interface ProcessSession extends AutoCloseable {
  OutputStream stdin();
  CompletableFuture<Integer> awaitExit();
  void terminate();
  void killWorkload();
  long stdoutBytes();
  long stderrBytes();
  @Override void close();
}
