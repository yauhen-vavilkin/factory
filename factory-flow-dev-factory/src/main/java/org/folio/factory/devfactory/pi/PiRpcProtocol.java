package org.folio.factory.devfactory.pi;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Framing boundary for Pi's newline-delimited JSON protocol. */
final class PiRpcProtocol {
  private PiRpcProtocol() { }

  static final class Decoder {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final JsonMapper json = JsonMapper.builder().build();

    List<JsonNode> accept(byte[] bytes, int offset, int length) {
      pending.write(bytes, offset, length);
      byte[] all = pending.toByteArray();
      List<JsonNode> result = new ArrayList<>();
      int start = 0;
      for (int i = 0; i < all.length; i++) {
        if (all[i] != '\n') continue;
        String line = decode(all, start, i - start);
        start = i + 1;
        if (!line.strip().isEmpty()) {
          try {
            result.add(json.readTree(line));
          } catch (RuntimeException e) {
            throw new PiRpcException("Malformed Pi RPC JSON", e);
          }
        }
      }
      pending.reset();
      pending.write(all, start, all.length - start);
      return result;
    }

    List<JsonNode> finish() {
      if (pending.size() != 0) {
        String line = decode(pending.toByteArray(), 0, pending.size());
        if (!line.strip().isEmpty()) throw new PiRpcException("Incomplete Pi RPC frame");
      }
      return List.of();
    }

    private static String decode(byte[] bytes, int offset, int length) {
      try {
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
      } catch (CharacterCodingException e) {
        throw new PiRpcException("Malformed UTF-8 in Pi RPC frame", e);
      }
    }
  }

  static final class PiRpcException extends IllegalStateException {
    PiRpcException(String message) { super(message); }
    PiRpcException(String message, Throwable cause) { super(message, cause); }
  }
}
