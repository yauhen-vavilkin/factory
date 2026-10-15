package org.folio.factory.devfactory.candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Factory-authored identity. The patch is raw textual Git binary-patch format. */
public record Candidate(String repository, String baseSha, String treeSha, String patchSha256, String patch, String state) {
    public Candidate {
        if (repository == null || !repository.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid repository key");
        if (baseSha == null || !baseSha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid base SHA");
        if (treeSha == null || !treeSha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid tree SHA");
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new IllegalArgumentException("Candidate exceeds MVP artifact limit");
        if (!sha256(patch).equals(patchSha256)) throw new IllegalArgumentException("Candidate patch digest mismatch");
        if (!"CANDIDATE_UNVERIFIED".equals(state)) throw new IllegalArgumentException("Frozen candidate must be unverified");
    }
    public static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
