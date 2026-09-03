package org.folio.factory.sandbox.tools;

import java.nio.charset.StandardCharsets;

public final class OutputLimiter {

  public static final int MAX_OUTPUT_BYTES = 50 * 1024;

  private OutputLimiter() {
  }

  public static String truncate(String output) {
    if (output == null) {
      return "";
    }
    byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_OUTPUT_BYTES) {
      return output;
    }
    int omitted = bytes.length - MAX_OUTPUT_BYTES;
    String marker = "\n[output truncated: " + omitted + " bytes omitted]";
    int limit = MAX_OUTPUT_BYTES - marker.getBytes(StandardCharsets.UTF_8).length;
    while (limit > 0 && (bytes[limit] & 0xC0) == 0x80) {
      limit--;
    }
    return new String(bytes, 0, limit, StandardCharsets.UTF_8) + marker;
  }
}
