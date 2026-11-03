package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.BuildEnvironment;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.DockerWorkloads;
import org.folio.factory.devfactory.runtime.InstallMode;
import org.folio.factory.devfactory.runtime.PinnedLockfile;
import org.folio.factory.devfactory.runtime.Processes;
import org.folio.factory.devfactory.runtime.Toolchain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A module without yarn.lock, on real containers and a throwaway npm registry: the starting build pins
 * 1.0.0, a newer 1.1.0 is then published, and verification still installs and checks 1.0.0.
 */
@EnabledIfEnvironmentVariable(named = "FACTORY_DOCKER_TEST", matches = "true")
class PinnedLockfileDockerTest {
    private static final String NODE_IMAGE = "node:22.22.0-bookworm";
    private static final String REGISTRY_SCRIPT = """
            const http = require('http'), crypto = require('crypto'), cp = require('child_process');
            const fs = require('fs'), os = require('os'), path = require('path');
            const built = {};
            const published = () => JSON.parse(fs.readFileSync('/srv/versions.json', 'utf8'));
            function tarball(version) {
              if (built[version]) return built[version];
              const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pkg-'));
              fs.mkdirSync(path.join(dir, 'package'));
              fs.writeFileSync(path.join(dir, 'package', 'package.json'),
                  JSON.stringify({ name: '@folio/pinned-fixture', version }));
              return built[version] = cp.execSync('tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner'
                  + ' -cf - -C ' + dir + ' package | gzip -n', { shell: '/bin/sh', maxBuffer: 1 << 20 });
            }
            http.createServer((req, res) => {
              const url = decodeURIComponent(req.url);
              const base = 'http://' + req.headers.host;
              if (url === '/@folio/pinned-fixture') {
                const versions = published(), meta = { name: '@folio/pinned-fixture', versions: {},
                  'dist-tags': { latest: versions[versions.length - 1] } };
                for (const v of versions) meta.versions[v] = { name: '@folio/pinned-fixture', version: v, dist: {
                  tarball: base + '/@folio/pinned-fixture/-/pinned-fixture-' + v + '.tgz',
                  shasum: crypto.createHash('sha1').update(tarball(v)).digest('hex') } };
                res.setHeader('content-type', 'application/json');
                return res.end(JSON.stringify(meta));
              }
              const m = url.match(/^\\/@folio\\/pinned-fixture\\/-\\/pinned-fixture-(.+)\\.tgz$/);
              if (m && published().includes(m[1])) return res.end(tarball(m[1]));
              res.statusCode = 404; res.end('{}');
            }).listen(4873, '0.0.0.0');
            """;
    private static final String CHECK_SCRIPT = """
            const fs = require('fs');
            const version = require('@folio/pinned-fixture/package.json').version;
            const ok = version === '1.0.0';
            fs.mkdirSync('artifacts/jest-junit', { recursive: true });
            fs.writeFileSync('artifacts/jest-junit/junit.xml', '<?xml version="1.0"?><testsuites tests="1" failures="'
                + (ok ? 0 : 1) + '" errors="0"><testsuite name="pinned" tests="1" failures="' + (ok ? 0 : 1)
                + '" errors="0" skipped="0"><testcase classname="pinned" name="installs 1.0.0 (got ' + version + ')">'
                + (ok ? '' : '<failure message="got ' + version + '"/>') + '</testcase></testsuite></testsuites>');
            console.log('installed ' + version);
            process.exit(ok ? 0 : 1);
            """;

    private final UUID executionId = UUID.randomUUID();
    private final String volume = "factory-pinned-test-" + executionId;
    private final String registry = "factory-pinned-registry-" + executionId;
    @TempDir Path root;

    @AfterEach void cleanup() {
        Processes.run(null, List.of("docker", "rm", "--force", registry), 30);
        Processes.run(null, List.of("docker", "volume", "rm", volume), 30);
    }

    @Test void fixtureWithoutALockfileVerifiesWithThePinnedVersionAfterANewerSnapshotIsPublished() throws Exception {
        String registryUrl = startRegistry(List.of("1.0.0"));
        var runtime = new DevRuntimeProperties(Map.of("jest", List.of("node", "check.js")), null, 600, null, Map.of(),
                Map.of("jest", Toolchain.YARN), new DevRuntimeProperties.Yarn(volume, registryUrl));
        var environment = runtime.environment("jest");

        Path original = Files.createDirectories(root.resolve("owner/source.git"));
        CandidateFreezer.git(original, "init", "--quiet");
        Files.writeString(original.resolve("package.json"), """
                {"name":"fixture","version":"1.0.0","private":true,"dependencies":{"@folio/pinned-fixture":"^1.0.0"}}
                """);
        Files.writeString(original.resolve("check.js"), CHECK_SCRIPT);
        CandidateFreezer.git(original, "add", ".");
        CandidateFreezer.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                "commit", "-qm", "base");
        String base = CandidateFreezer.git(original, "rev-parse", "HEAD").strip();
        Files.writeString(original.resolve("NOTES.md"), "candidate change\n");
        CandidateFreezer.git(original, "add", ".");
        String patch = CandidateFreezer.git(original, "diff", "--cached", "--binary", "--full-index", base);
        var candidate = new Candidate("source", base, CandidateFreezer.git(original, "write-tree").strip(),
                Candidate.sha256(patch), patch, "CANDIDATE_UNVERIFIED");

        // Starting build: no yarn.lock at base, so the install resolves and the lockfile is read out.
        Path pristine = new CandidateFreezer().checkout(root + "/owner/source.git", base);
        PinnedLockfile pinned;
        try (var scope = new DockerWorkloads().beginStep(executionId, "baseline");
             var baseline = scope.createTrusted(NODE_IMAGE, pristine, environment)) {
            var result = baseline.execute(runtime.verificationCommand("jest", InstallMode.RESOLVE), 600);
            assertThat(result.exitCode()).as(result.diagnostics()).isZero();
            pinned = baseline.readLockfile().orElseThrow()
                    .forPackageJson(CandidateFreezer.trackedBlob(pristine, "package.json"));
        }
        assertThat(pinned.content()).contains("pinned-fixture-1.0.0.tgz").contains("version \"1.0.0\"");

        publish(List.of("1.0.0", "1.1.0"));

        // Without the pin the same install now picks up the newer snapshot, and the check notices.
        try (var scope = new DockerWorkloads().beginStep(executionId, "control");
             var drifted = scope.createSeeded(NODE_IMAGE, pristine, environment, "verification")) {
            var result = drifted.execute(runtime.verificationCommand("jest"), 600);
            assertThat(result.exitCode()).as(result.diagnostics()).isNotZero();
            assertThat(result.output()).contains("installed 1.1.0");
        }

        // With the pin seeded, Factory's verifier installs frozen against it and passes.
        var properties = new DevFactoryProperties(root.toString(), new TreeMap<>(Map.of("source",
                new DevFactoryProperties.Repository("owner/source", "master", NODE_IMAGE, "jest", List.of("TASK"),
                        List.of()))));
        var receipt = new CandidateVerifier(properties, runtime, new CandidateFreezer(), new DockerWorkloads())
                .verify(executionId, "verify", candidate, Optional.of(pinned));

        assertThat(receipt.result()).as(receipt.output()).isEqualTo("PASS");
        assertThat(receipt.lockfileSha256()).isEqualTo(pinned.sha256());
        assertThat(receipt.argv().get(2)).contains("'--frozen-lockfile'");
        receipt.requireVerified(executionId.toString(), candidate);
        assertThat(new VerificationPolicy(properties, runtime).matches(candidate, receipt)).isTrue();
        CandidateFreezer.cleanup(pristine);
    }

    @Test void seededLockfileBelongsToTheWorkloadUserSoALaterInstallCanRewriteIt() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"fixture\",\"private\":true}");
        var environment = new BuildEnvironment(Toolchain.YARN, volume, DevRuntimeProperties.Yarn.DEFAULT_FOLIO_REGISTRY);
        try (var scope = new DockerWorkloads().beginStep(executionId, "seed");
             var workload = scope.createSeeded(NODE_IMAGE, root, environment, "coding")) {
            workload.seedLockfile(PinnedLockfile.of("# yarn lockfile v1\n".getBytes()));

            assertThat(workload.execute(List.of("sh", "-c", "echo '# changed' >> yarn.lock && head -1 yarn.lock"), 30)
                    .output()).contains("# yarn lockfile v1");
            assertThat(workload.execute(List.of("stat", "-c", "%u", "yarn.lock"), 30).output().strip())
                    .isEqualTo(workload.execute(List.of("id", "-u"), 30).output().strip());
        }
    }

    @Test void aSymlinkLeftWhereTheLockfileShouldBeIsRefusedNotFollowed() throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"fixture\",\"private\":true}");
        var environment = new BuildEnvironment(Toolchain.YARN, volume, DevRuntimeProperties.Yarn.DEFAULT_FOLIO_REGISTRY);
        try (var scope = new DockerWorkloads().beginStep(executionId, "symlink");
             var workload = scope.createSeeded(NODE_IMAGE, root, environment, "verification")) {
            assertThat(workload.readLockfile()).isEmpty();

            workload.execute(List.of("ln", "-s", "/etc/passwd", "yarn.lock"), 30);

            org.assertj.core.api.Assertions.assertThatThrownBy(workload::readLockfile)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not a regular file");
        }
    }

    private String startRegistry(List<String> versions) throws Exception {
        Path script = Files.writeString(root.resolve("registry.js"), REGISTRY_SCRIPT);
        Processes.run(null, List.of("docker", "create", "--name", registry, NODE_IMAGE, "node", "/srv/registry.js"), 120)
                .requireSuccess();
        Processes.run(null, List.of("docker", "cp", script.toString(), registry + ":/srv/registry.js"), 30).requireSuccess();
        publish(versions);
        Processes.run(null, List.of("docker", "start", registry), 60).requireSuccess();
        String ip = Processes.run(null, List.of("docker", "inspect", "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", registry), 30).requireSuccess().output().strip();
        assertThat(ip).matches("[0-9.]+");
        for (int attempt = 0; attempt < 40; attempt++) {
            var probe = Processes.run(null, List.of("docker", "exec", registry, "node", "-e",
                    "require('http').get('http://127.0.0.1:4873/@folio%2Fpinned-fixture',"
                            + "r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"), 30);
            if (probe.exitCode() == 0) return "http://" + ip + ":4873/";
            Thread.sleep(500);
        }
        throw new IllegalStateException("Throwaway registry did not start");
    }

    private void publish(List<String> versions) throws Exception {
        Path file = Files.writeString(root.resolve("versions.json"),
                "[" + String.join(",", versions.stream().map(v -> "\"" + v + "\"").toList()) + "]");
        Processes.run(null, List.of("docker", "cp", file.toString(), registry + ":/srv/versions.json"), 30).requireSuccess();
    }
}
