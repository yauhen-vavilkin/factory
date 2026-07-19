package org.folio.factory.core.limits;

import org.springframework.stereotype.Component;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Derives a stable, flow-agnostic deduplication key from a trigger payload. The key
 * is scoped to a flow at the persistence layer (unique index on flow_id + dedup_key),
 * so this only encodes the payload identity: the first configured JSON pointer that
 * resolves to a non-blank scalar, else a sha256 of the serialized payload.
 *
 * <p>The sha256 fallback keys off the serialized bytes as-is: a re-fired webhook from
 * the same source produces byte-identical JSON and therefore the same key, which is
 * the case dedup targets. Two semantically-equal payloads with reordered keys would
 * hash differently and not dedupe — an acceptable edge case for Milestone 1.</p>
 */
@Component
public class DedupKeyDeriver {

    private static final int MAX_KEY_LENGTH = 255;

    private final LimitsProperties limits;
    private final JsonMapper jsonMapper;
    private final List<JsonPointer> idPointers;

    public DedupKeyDeriver(LimitsProperties limits, JsonMapper jsonMapper) {
        this.limits = limits;
        this.jsonMapper = jsonMapper;
        this.idPointers = compile(limits.dedup().idPointers());
    }

    /**
     * Compiled at construction so a malformed configured pointer fails the application
     * at startup instead of throwing on every incoming trigger at runtime.
     */
    private static List<JsonPointer> compile(List<String> pointers) {
        List<JsonPointer> compiled = new ArrayList<>(pointers.size());
        for (String pointer : pointers) {
            try {
                compiled.add(JsonPointer.compile(pointer));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid JSON pointer '" + pointer
                        + "' in factory.limits.dedup.id-pointers: " + e.getMessage(), e);
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * @return a dedup key, or {@code null} when dedup is disabled (meaning: never dedupe).
     */
    public String derive(JsonNode payload) {
        if (!limits.dedup().enabled()) {
            return null;
        }
        for (JsonPointer pointer : idPointers) {
            JsonNode node = payload == null ? null : payload.at(pointer);
            if (node != null && node.isValueNode()) {
                String value = node.asString("");
                if (!value.isBlank()) {
                    return bounded(pointer + "=" + value);
                }
            }
        }
        byte[] serialized = payload == null ? new byte[0] : jsonMapper.writeValueAsBytes(payload);
        return "sha256:" + sha256Hex(serialized);
    }

    private String bounded(String key) {
        if (key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return "sha256:" + sha256Hex(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
