package org.folio.factory.devfactory.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Stable JSON bytes and SHA-256 independent of object key insertion order. */
public final class CanonicalJson {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private CanonicalJson() {
  }

  public static byte[] bytes(JsonNode value) {
    return JSON.writeValueAsBytes(sort(value));
  }

  public static String sha256(JsonNode value) {
    return sha256(bytes(value));
  }

  public static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(64);
      for (byte value : digest) {
        result.append(Character.forDigit((value >>> 4) & 0xf, 16));
        result.append(Character.forDigit(value & 0xf, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM without SHA-256", e);
    }
  }

  public static JsonNode sort(JsonNode value) {
    if (value == null || value.isNull() || value.isValueNode()) {
      return value;
    }
    if (value.isArray()) {
      ArrayNode array = JSON.createArrayNode();
      value.forEach(item -> array.add(sort(item)));
      return array;
    }
    ObjectNode object = JSON.createObjectNode();
    List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
    value.properties().forEach(fields::add);
    fields.sort(Comparator.comparing(Map.Entry::getKey));
    fields.forEach(field -> object.set(field.getKey(), sort(field.getValue())));
    return object;
  }
}
