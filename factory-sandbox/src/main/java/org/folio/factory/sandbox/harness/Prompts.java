package org.folio.factory.sandbox.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Prompts {

  public static final String CODING_WORKER_PROMPT = "prompts/coding-worker.md";

  private Prompts() {
  }

  public static String codingWorker() {
    return load(CODING_WORKER_PROMPT);
  }

  public static final String FOLIO_CONTEXT_PROMPT = "prompts/folio-context.md";

  public static String folioContext() {
    return load(FOLIO_CONTEXT_PROMPT);
  }

  public static String codingWorkerSystemPrompt() {
    return codingWorker() + "\n" + folioContext();
  }

  public static String load(String path) {
    try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("classpath resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read " + path, e);
    }
  }
}
