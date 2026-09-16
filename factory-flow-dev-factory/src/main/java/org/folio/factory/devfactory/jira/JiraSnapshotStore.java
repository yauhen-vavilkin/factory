package org.folio.factory.devfactory.jira;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable, write-once snapshot storage keyed by issue and content digest:
 * {@code <root>/<ISSUE-KEY>/<contentSha256>.json}. The first fetch of a given
 * content wins; a later identical fetch keeps that file, so a stored snapshot
 * is never rewritten.
 */
public final class JiraSnapshotStore {
  private final Path root;
  private final JsonMapper json = JsonMapper.builder().build();

  public JiraSnapshotStore(Path root) {
    this.root = root;
  }

  public record Stored(Path path, String content) {
  }

  public Path path(String issueKey, String contentSha256) {
    return root.resolve(issueKey).resolve(contentSha256 + ".json");
  }

  public Stored store(JiraTaskSnapshot snapshot) {
    Path target = path(snapshot.issueKey(), snapshot.contentSha256());
    Path directory = target.getParent();
    try {
      if (Files.isRegularFile(target)) {
        return new Stored(target, Files.readString(target));
      }
      Files.createDirectories(directory);
      String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
      Path partial = directory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".partial");
      Files.writeString(partial, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try {
        // A hard link is created atomically and fails if the target exists, so a
        // concurrent identical fetch that landed first is kept, never replaced.
        Files.createLink(target, partial);
      } catch (FileAlreadyExistsException e) {
        return new Stored(target, Files.readString(target));
      } finally {
        Files.deleteIfExists(partial);
      }
      return new Stored(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot store Jira snapshot " + target, e);
    }
  }
}
