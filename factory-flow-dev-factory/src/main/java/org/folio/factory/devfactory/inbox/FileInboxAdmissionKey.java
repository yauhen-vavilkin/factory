package org.folio.factory.devfactory.inbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admission identity of an inbox task file (T22 R1).
 *
 * <p><strong>Definition (frozen; asserted by {@code FileInboxAdmissionKeyTest}
 * and the trigger-level replay/revision tests):</strong> the admitted revision
 * of a task is its <em>normalized content</em> — the eight parsed fields
 * ({@code id, repo, base, branch, goal, acceptance, constraints, notes}) with
 * parser defaults already applied — not its file name, mtime or raw bytes. The
 * admission key is {@code "file.inbox:" + SHA-256} of the canonical payload
 * JSON, which {@code FileInboxTrigger.payload} builds in a fixed field order.
 *
 * <p>Consequences:
 * <ul>
 *   <li>Re-dropping the same content — byte-identical or merely reordered,
 *       re-indented, with comments moved — is a <em>replay</em> of the same
 *       admitted revision under any file name: it must return the already
 *       admitted execution, never a second one.</li>
 *   <li>An <em>intentional new revision</em> is a change to the normalized
 *       content (revised goal, an extra acceptance criterion, a different
 *       branch, …): different hash, different admission key, a new execution
 *       with its own audit trail.</li>
 * </ul>
 */
public final class FileInboxAdmissionKey {

    public static final String PREFIX = "file.inbox:";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FileInboxAdmissionKey() {
    }

    public static String of(JsonNode normalizedPayload) {
        return PREFIX + sha256Hex(JSON.writeValueAsString(normalizedPayload));
    }

    private static String sha256Hex(String canonical) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
        byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
