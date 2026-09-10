package org.folio.factory.sandbox.api;

@FunctionalInterface
public interface ProcessOutputSink {
  void accept(Stream stream, byte[] bytes, int offset, int length);

  enum Stream { STDOUT, STDERR }
}
