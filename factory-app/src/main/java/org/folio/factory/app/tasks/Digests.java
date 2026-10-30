package org.folio.factory.app.tasks;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex helper shared by the task fingerprint and identity hashes
 * (source authority, task input/destination, runtime identity).
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Digests {

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
