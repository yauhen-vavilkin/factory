package org.folio.factory.devfactory.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Only owned containers, with private writable storage and no host mounts. */
public class DockerWorkloads {
    private static final Logger log = LoggerFactory.getLogger(DockerWorkloads.class);
    private static final String MAVEN_REPOSITORY = "/tmp/factory-home/.m2/repository";
    private final Set<String> activeSteps = ConcurrentHashMap.newKeySet();

    /** Claims this step within this process before looking for crashed predecessors. */
    public StepScope beginStep(UUID executionId, String stepId) {
        if (executionId == null || stepId == null || !stepId.matches("[a-zA-Z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid Factory workload ownership");
        String key = executionId + "/" + stepId;
        if (!activeSteps.add(key)) throw new ActiveStepException();
        try {
            reconcile(executionId, stepId);
            return new StepScope(executionId, stepId, key);
        } catch (RuntimeException e) {
            activeSteps.remove(key);
            throw e;
        }
    }

    /** A local duplicate must never be converted into a normal coding failure. */
    public static final class ActiveStepException extends IllegalStateException {
        public ActiveStepException() { super("Developer step is already active in this process"); }
    }

    private void reconcile(UUID executionId, String stepId) {
        var result = Processes.run(null, List.of("docker", "ps", "-aq",
                "--filter", "label=factory.dev-owned=true",
                "--filter", "label=factory.dev-execution=" + executionId,
                "--filter", "label=factory.dev-step=" + stepId), 30).requireSuccess();
        for (String id : result.output().split("\\R")) {
            if (id.isBlank()) continue;
            if (!id.matches("[a-fA-F0-9]{12,64}")) throw new IllegalStateException("Invalid Docker workload identity");
            log.warn("Removing stale Developer workload {} for execution {} step {}", id, executionId, stepId);
            Processes.run(null, List.of("docker", "rm", "--force", id), 30).requireSuccess();
        }
    }

    public final class StepScope implements AutoCloseable {
        private final UUID executionId;
        private final String stepId;
        private final String key;
        private boolean closed;

        private StepScope(UUID executionId, String stepId, String key) {
            this.executionId = executionId;
            this.stepId = stepId;
            this.key = key;
        }

        public Workload createTrusted(String image, Path source, String mavenCacheVolume) {
            if (closed) throw new IllegalStateException("Developer step scope is closed");
            return DockerWorkloads.this.createTrusted(image, source, mavenCacheVolume, executionId, stepId);
        }

        public Workload createSeeded(String image, Path source, String mavenCacheVolume, String role) {
            if (closed) throw new IllegalStateException("Developer step scope is closed");
            if (!role.equals("coding") && !role.equals("verification"))
                throw new IllegalArgumentException("Invalid Factory workload role");
            return DockerWorkloads.this.create(image, source, MavenCache.READ_ONLY_SEED,
                    mavenCacheVolume, executionId, stepId, role);
        }

        @Override public void close() {
            closed = true;
            activeSteps.remove(key);
        }
    }

    private Workload createTrusted(String image, Path source, String mavenCacheVolume,
                                   UUID executionId, String stepId) {
        ensureWritableCache(image, mavenCacheVolume, workloadUser(source), executionId, stepId);
        return create(image, source, MavenCache.TRUSTED_WRITABLE, mavenCacheVolume,
                executionId, stepId, "baseline");
    }
    private Workload create(String image, Path source, MavenCache cache, String mavenCacheVolume,
                            UUID executionId, String stepId, String role) {
        String name = "factory-dev-" + UUID.randomUUID();
        String user = workloadUser(source);
        Processes.run(null, createCommand(image, name, user, cache, mavenCacheVolume,
                executionId, stepId, role), 120).requireSuccess();
        var workload = new Workload(name);
        try {
            Processes.run(null, List.of("docker", "start", name), 60).requireSuccess();
            if (cache == MavenCache.READ_ONLY_SEED) seedMavenRepository(name);
            workload.copy(source.resolve(".").toString(), "/workspace");
            // Docker creates WORKDIR as root even when the workload runs as the
            // trusted checkout UID/GID. Allow that user to create build output and
            // new source files at the workspace root; the container remains private.
            Processes.run(null, List.of("docker", "exec", "--user", "0:0", name,
                    "chmod", "0777", "/workspace"), 30).requireSuccess();
            return workload;
        } catch (RuntimeException e) { workload.close(); throw e; }
    }

    private static void ensureWritableCache(String image, String volume, String user,
                                            UUID executionId, String stepId) {
        // This short-lived Factory-controlled helper touches only the configured
        // Factory-owned Maven volume, before a trusted baseline mounts it writable.
        // The root directory alone is insufficient: cached artifacts and nested
        // directories can belong to a previous UID and block the checkout user.
        Processes.run(null, List.of("docker", "run", "--rm", "--user", "0:0",
                "--label", "factory.dev-owned=true", "--label", "factory.dev-execution=" + executionId,
                "--label", "factory.dev-step=" + stepId, "--label", "factory.dev-role=baseline-cache",
                "--entrypoint", "/bin/sh",
                "--mount", "type=volume,source=" + volume + ",target=/cache", image,
                "-c", "chown -R \"$1\" /cache && chmod -R u+rwX /cache", "--", user), 120).requireSuccess();
    }

    private static void seedMavenRepository(String name) {
        Processes.run(null, List.of("docker", "exec", name, "/bin/sh", "-c",
                "mkdir -p /tmp/factory-home/.m2/repository "
                        + "&& cp -a /tmp/factory-m2-seed/. /tmp/factory-home/.m2/repository/"), 120)
                .requireSuccess();
    }

    static List<String> createCommand(String image, String name, String user, MavenCache cache, String volume,
                                      UUID executionId, String stepId, String role) {
        var command = new ArrayList<>(List.of("docker", "create", "--name", name,
                "--label", "factory.dev-owned=true",
                "--label", "factory.dev-execution=" + executionId,
                "--label", "factory.dev-step=" + stepId,
                "--label", "factory.dev-role=" + role,
                "--pids-limit", "512", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", user, "--env", "HOME=/tmp/factory-home", "--env",
                "MAVEN_OPTS=-Dmaven.repo.local=" + MAVEN_REPOSITORY, "--workdir", "/workspace"));
        if (cache == MavenCache.TRUSTED_WRITABLE) {
            command.addAll(List.of("--mount", "type=volume,source=" + volume
                    + ",target=/tmp/factory-home/.m2/repository"));
        } else if (cache == MavenCache.READ_ONLY_SEED) {
            command.addAll(List.of("--mount", "type=volume,source=" + volume
                    + ",target=/tmp/factory-m2-seed,readonly"));
        }
        command.addAll(List.of("--entrypoint", "/bin/sh", image, "-c", cache.startupCommand()));
        return List.copyOf(command);
    }

    enum MavenCache {
        NONE("mkdir -p /tmp/factory-home; exec sleep infinity"),
        TRUSTED_WRITABLE("mkdir -p /tmp/factory-home/.m2/repository; exec sleep infinity"),
        READ_ONLY_SEED("mkdir -p /tmp/factory-home; exec sleep infinity");
        private final String startupCommand;
        MavenCache(String startupCommand) { this.startupCommand = startupCommand; }
        String startupCommand() { return startupCommand; }
    }

    private static String workloadUser(Path source) {
        try {
            Number uid = (Number) java.nio.file.Files.getAttribute(source, "unix:uid");
            Number gid = (Number) java.nio.file.Files.getAttribute(source, "unix:gid");
            return uid + ":" + gid;
        } catch (java.io.IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("Cannot resolve trusted workspace ownership", e);
        }
    }
    public static final class Workload implements AutoCloseable {
        private final String name;
        private boolean stopped;
        private Workload(String name) { this.name = name; }
        public String name() { return name; }
        public void copy(String local, String destination) {
            Processes.run(null, List.of("docker", "cp", local, name + ":" + destination), 120).requireSuccess();
        }
        public Processes.Result execute(List<String> argv, int timeout) {
            return execute(argv, timeout, Processes.OUTPUT_LIMIT);
        }
        public Processes.Result execute(List<String> argv, int timeout, int outputLimit) {
            return execute(argv, timeout, outputLimit, null);
        }
        public Processes.Result execute(List<String> argv, int timeout, int outputLimit,
                                        java.util.function.Consumer<String> stdoutLine) {
            if (stopped) throw new IllegalStateException("Workload is stopped");
            var command = new ArrayList<>(List.of("docker", "exec", "--workdir", "/workspace", name));
            command.addAll(argv);
            return Processes.run(null, command, timeout, java.util.Map.of(), outputLimit, stdoutLine);
        }
        public void stop() {
            if (!stopped) Processes.run(null, List.of("docker", "stop", "--time", "2", name), 30).requireSuccess();
            stopped = true;
        }
        public void export(Path destination) {
            if (!stopped) throw new IllegalStateException("Stop workload before export");
            Processes.run(null, List.of("docker", "cp", name + ":/workspace/.", destination.toString()), 120).requireSuccess();
        }
        @Override public void close() {
            try {
                Processes.run(null, List.of("docker", "rm", "--force", name), 30).requireSuccess();
            } catch (RuntimeException e) {
                log.warn("Could not remove owned workload {}: {}", name, e.getMessage());
            }
        }
    }
}
