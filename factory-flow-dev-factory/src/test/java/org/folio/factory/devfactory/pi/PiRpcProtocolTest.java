package org.folio.factory.devfactory.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PiRpcProtocolTest {
  @Test
  void decodesSplitMultibyteUtf8OnlyAfterCompleteFrame() {
    byte[] frame = "{\"id\":\"é\"}\n".getBytes(StandardCharsets.UTF_8);
    PiRpcProtocol.Decoder decoder = new PiRpcProtocol.Decoder();
    int split = "{\"id\":\"".getBytes(StandardCharsets.UTF_8).length + 1;
    assertThat(decoder.accept(frame, 0, split)).isEmpty();
    List<JsonNode> messages = decoder.accept(frame, split, frame.length - split);
    assertThat(messages).singleElement().extracting(n -> n.path("id").asString()).isEqualTo("é");
  }

  @Test
  void surfacesMalformedJson() {
    assertThatThrownBy(() -> new PiRpcProtocol.Decoder().accept(
        "not-json\n".getBytes(StandardCharsets.UTF_8), 0, 9))
        .isInstanceOf(PiRpcProtocol.PiRpcException.class)
        .hasMessageContaining("Malformed Pi RPC JSON");
  }

  @Test
  void retainsResponsesForIdBasedOutOfOrderCorrelation() {
    PiRpcProtocol.Decoder decoder = new PiRpcProtocol.Decoder();
    List<JsonNode> messages = decoder.accept(
        "{\"id\":\"b\",\"result\":2}\n{\"id\":\"a\",\"result\":1}\n"
        .getBytes(StandardCharsets.UTF_8), 0,
        "{\"id\":\"b\",\"result\":2}\n{\"id\":\"a\",\"result\":1}\n".getBytes(StandardCharsets.UTF_8).length);
    assertThat(messages).extracting(n -> n.path("id").asString()).containsExactly("b", "a");
    assertThat(messages.get(0).path("result").asInt()).isEqualTo(2);
  }
}
