package org.folio.factory.devfactory.candidate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Predicate;

/** Factory-authored identity. The patch is raw textual Git binary-patch format. */
public record Candidate(String repository, String baseSha, String treeSha, String patchSha256, String patch,
        String state) {
    public static final String UNVERIFIED = "CANDIDATE_UNVERIFIED";

    public Candidate {
        if (repository == null || !repository.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid repository key");
        if (baseSha == null || !baseSha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid base SHA");
        if (treeSha == null || !treeSha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid tree SHA");
        if (patch == null || patch.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024)
            throw new IllegalArgumentException("Candidate exceeds MVP artifact limit");
        if (!sha256(patch).equals(patchSha256)) throw new IllegalArgumentException("Candidate patch digest mismatch");
        if (!UNVERIFIED.equals(state))
            throw new IllegalArgumentException("Frozen candidate must be unverified");
    }
    /**
     * The file of the first line the patch adds that {@code match} accepts. File headers and binary patches are not
     * added lines.
     */
    public Optional<String> findFileAdding(Predicate<String> match) {
        var file = "";
        var header = false;
        for (var line : patch.lines().toList()) {
            if (line.startsWith("diff --git ")) {
                header = true;
            } else if (header && line.startsWith("+++ ")) {
                file = newPath(line.substring("+++ ".length()));
            } else if (line.startsWith("@@")) {
                header = false;
            } else if (!header && line.startsWith("+") && match.test(line.substring(1))) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /** "b/src/A.java", or "\"b/src/A B.java\"" for a name Git quotes. */
    private static String newPath(String header) {
        var path = header.length() > 1 && header.startsWith("\"") && header.endsWith("\"")
                ? header.substring(1, header.length() - 1) : header;
        return path.startsWith("b/") ? path.substring(2) : path;
    }

    public static String sha256(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
