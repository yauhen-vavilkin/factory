package org.folio.factory.devfactory.candidate;

import org.folio.factory.devfactory.runtime.Processes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ignores coding Git metadata and derives every hash in a fresh trusted checkout. */
public class CandidateFreezer {
    private static final Logger log = LoggerFactory.getLogger(CandidateFreezer.class);
    private static final Set<String> GENERATED = Set.of("target", "node_modules", ".pi");
    public Path checkout(String sourceUrl, String baseSha) {
        if (!baseSha.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid base SHA");
        Path directory = temporary("factory-dev-base-");
        try {
            git(directory, "init", "--quiet");
            git(directory, "-c", "credential.helper=", "fetch", "--quiet", "--depth=1", sourceUrl, baseSha);
            git(directory, "checkout", "--quiet", "--detach", "FETCH_HEAD");
            if (!git(directory, "rev-parse", "HEAD").strip().equals(baseSha)) throw new IllegalStateException("Base SHA mismatch");
            if (!git(directory, "ls-files", "--stage").lines().noneMatch(line -> line.startsWith("160000")))
                throw new IllegalStateException("Submodules unsupported for MVP");
            return directory;
        } catch (RuntimeException e) { cleanup(directory); throw e; }
    }
    public Candidate freeze(String repository, String sourceUrl, String baseSha, Path exported) {
        Path trusted = checkout(sourceUrl, baseSha);
        try {
            Set<Path> tracked = trackedPaths(trusted);
            try (var entries = Files.list(trusted)) {
                entries.filter(path -> !path.getFileName().toString().equals(".git")).forEach(CandidateFreezer::delete);
            }
            mirror(exported, trusted, tracked);
            git(trusted, "add", "--all", "--", ".");
            String tree = git(trusted, "write-tree").strip();
            String baseTree = git(trusted, "rev-parse", baseSha + "^{tree}").strip();
            if (tree.equals(baseTree)) throw new IllegalStateException("Candidate contains no changes");
            String patch = git(trusted, "diff", "--cached", "--binary", "--full-index", "--no-ext-diff", "--no-textconv", baseSha, "--");
            if (patch.isEmpty()) throw new IllegalStateException("Candidate contains no patch");
            Candidate candidate = new Candidate(repository, baseSha, tree, Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");
            Path roundTrip = reconstruct(sourceUrl, candidate);
            cleanup(roundTrip);
            return candidate;
        } catch (IOException e) { throw new IllegalStateException("Cannot freeze candidate", e); }
        finally { cleanup(trusted); }
    }
    public Path reconstruct(String sourceUrl, Candidate candidate) {
        Path directory = checkout(sourceUrl, candidate.baseSha());
        Path patch = temporary("factory-dev-patch-").resolve("candidate.patch");
        try {
            Files.writeString(patch, candidate.patch());
            if (!candidate.patch().isEmpty()) git(directory, "apply", "--index", "--binary", "--", patch.toString());
            if (!git(directory, "write-tree").strip().equals(candidate.treeSha())) throw new IllegalStateException("Reconstructed candidate tree mismatch");
            return directory;
        } catch (IOException | RuntimeException e) { cleanup(directory); throw new IllegalStateException("Candidate reconstruction failed", e); }
        finally { cleanup(patch.getParent()); }
    }
    private void mirror(Path source, Path destination, Set<Path> tracked) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path relative = source.relativize(entry);
                if (relative.toString().isEmpty()) continue;
                boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                if (excluded(relative, directory, tracked)) continue;
                Path target = destination.resolve(relative);
                if (directory) Files.createDirectories(target);
                else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                    Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                } else throw new IllegalStateException("Unsupported candidate file: " + relative);
            }
        }
    }
    private static boolean excluded(Path relative, boolean directory, Set<Path> tracked) {
        for (Path part : relative) if (part.toString().equals(".git")) return true;
        boolean generated = false;
        for (Path part : relative) if (GENERATED.contains(part.toString())) generated = true;
        if (!generated) return false;
        return directory ? tracked.stream().noneMatch(path -> path.startsWith(relative)) : !tracked.contains(relative);
    }
    private static Set<Path> trackedPaths(Path checkout) {
        Set<Path> tracked = new HashSet<>();
        String output = git(checkout, "ls-files", "-z");
        int start = 0;
        for (int end = output.indexOf('\0'); end >= 0; end = output.indexOf('\0', start)) {
            if (end > start) tracked.add(Path.of(output.substring(start, end)));
            start = end + 1;
        }
        return tracked;
    }
    public static String git(Path directory, String... args) {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        return Processes.run(directory, command, 180).requireSuccess().output();
    }
    public static Path temporary(String prefix) {
        try { return Files.createTempDirectory(prefix); }
        catch (IOException e) { throw new IllegalStateException("Cannot create temporary workspace", e); }
    }
    public static void delete(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException e) { throw new IllegalStateException("Cannot remove owned temporary path " + directory, e); }
    }

    /** Cleanup is never allowed to invalidate an already produced candidate or receipt. */
    public static void cleanup(Path directory) {
        try {
            delete(directory);
        } catch (RuntimeException e) {
            log.warn("Could not clean owned temporary path {}: {}", directory, e.getMessage());
        }
    }
}
