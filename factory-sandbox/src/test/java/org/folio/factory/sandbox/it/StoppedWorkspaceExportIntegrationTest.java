package org.folio.factory.sandbox.it;

import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.core.DockerSandboxService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class StoppedWorkspaceExportIntegrationTest {
  @TempDir Path source;

  @Test
  void stoppedCandidateSurvivesWithAllChangesAndFreshGitMetadata() throws Exception {
    Files.writeString(source.resolve("README.md"), "base\n");
    Files.writeString(source.resolve("deleted.txt"), "delete me\n");
    Files.writeString(source.resolve("mode.sh"), "#!/bin/sh\n");
    run("git", "init", "-q", "-b", "main");
    run("git", "config", "user.name", "test");
    run("git", "config", "user.email", "test@example.invalid");
    run("git", "add", ".");
    run("git", "commit", "-qm", "base");
    String base = run("git", "rev-parse", "HEAD").trim();
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    var transport = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig()).build();
    try (var docker = DockerClientImpl.getInstance(config, transport)) {
      var service = new DockerSandboxService(docker);
      var spec = new SandboxSpec("frozen-export", source.toUri().toString(), base, "candidate");
      SandboxHandle coding = service.create(spec);
      SandboxHandle exporter = null;
      try {
        var edit = service.exec(coding, "cd repo && "
            + "printf 'committed\\n' > README.md && git add README.md && git commit -qm edit && "
            + "printf 'staged\\n' >> README.md && git add README.md && "
            + "rm deleted.txt && chmod +x mode.sh && ln -s README.md link && "
            + "printf '\\000\\001\\002' > binary.dat && printf 'new\\n' > untracked.txt && "
            + "git config core.worktree /does-not-exist && "
            + "printf '#!/bin/sh\\nexit 99\\n' > .git/hooks/pre-commit && "
            + "chmod +x .git/hooks/pre-commit", 30);
        assertTrue(edit.ok(), edit.stderr());
        docker.killContainerCmd(coding.containerId()).exec();
        exporter = service.freezeForExport(coding, spec);
        assertFalse(docker.inspectContainerCmd(coding.containerId()).exec().getState().getRunning());
        assertEquals(base, service.exec(exporter, "cd repo && git rev-parse HEAD", 30).stdout().trim());
        var patch = service.exec(exporter,
            "cd repo && git add -A && git diff --cached --binary " + base, 30);
        assertTrue(patch.ok(), patch.stderr());
        for (String expected : new String[] {"+committed", "+staged", "deleted file mode",
            "new mode 100755", "new file mode 120000", "GIT binary patch", "untracked.txt"}) {
          assertTrue(patch.stdout().contains(expected), expected + " missing from " + patch.stdout());
        }
        assertTrue(service.exec(exporter, "cd repo && git commit -qm frozen", 30).ok(),
            "candidate Git config and hooks must not participate in export");
        System.out.println("STOPPED_EXPORT_PASS committed=true staged=true deleted=true binary=true "
            + "untracked=true modes=true symlinks=true fresh_git=true coding_restarted=false");
      } finally {
        if (exporter != null) service.teardown(exporter);
        service.teardown(coding);
      }
    }
  }

  private String run(String... argv) throws Exception {
    Process process = new ProcessBuilder(argv).directory(source.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }
}
