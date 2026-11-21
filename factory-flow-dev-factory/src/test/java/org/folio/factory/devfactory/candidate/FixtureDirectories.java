package org.folio.factory.devfactory.candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies a fixture tree, so a test that changes a Git fixture starts from one built once per class. */
public final class FixtureDirectories {

    private FixtureDirectories() {
    }

    public static void copy(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
