package org.folio.factory.devfactory.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Only owned containers, with private writable storage and no host mounts. */
public class DockerWorkloads {
    private static final Logger log = LoggerFactory.getLogger(DockerWorkloads.class);
    private static final String MAVEN_REPOSITORY = "/tmp/factory-home/.m2/repository";
    private static final String MAVEN_SEED = "/tmp/factory-m2-seed";
    static final String YARN_CACHE = "/tmp/factory-home/.yarn-cache";
    private static final String YARN_SEED = "/tmp/factory-yarn-seed";
    private static final String PARKED_NODE_MODULES = "/tmp/factory-parked-node_modules";
    private static final String STAGED_LOCKFILE = "/tmp/factory-pinned-yarn.lock";
    private final Set<String> activeSteps = ConcurrentHashMap.newKeySet();
    private final int seedTimeoutSeconds;

    public DockerWorkloads() {
        this(DevRuntimeProperties.DEFAULT_SEED_TIMEOUT_SECONDS);
    }

    public DockerWorkloads(int seedTimeoutSeconds) {
        if (seedTimeoutSeconds < 1) throw new IllegalArgumentException("Seed timeout must be positive");
        this.seedTimeoutSeconds = seedTimeoutSeconds;
    }

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

    /** Strict cleanup for a quiescent, owned feedback operation. Failure must remain visible. */
    public void cleanOwned(UUID executionId, String stepId) {
        if (activeSteps.contains(executionId + "/" + stepId))
            throw new IllegalStateException("Workload operation is still active");
        reconcile(executionId, stepId);
        var remaining = Processes.run(null, List.of("docker", "ps", "-aq",
                "--filter", "label=factory.dev-owned=true", "--filter", "label=factory.dev-execution=" + executionId,
                "--filter", "label=factory.dev-step=" + stepId), 30).requireSuccess();
        if (!remaining.output().isBlank()) throw new IllegalStateException("Owned containers remain");
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

        public Workload createTrusted(String image, Path source, BuildEnvironment environment) {
            if (closed) throw new IllegalStateException("Developer step scope is closed");
            return DockerWorkloads.this.createTrusted(image, source, environment, executionId, stepId);
        }

        public Workload createSeeded(String image, Path source, BuildEnvironment environment, String role) {
            if (closed) throw new IllegalStateException("Developer step scope is closed");
            if (!role.equals("coding") && !role.equals("verification"))
                throw new IllegalArgumentException("Invalid Factory workload role");
            return DockerWorkloads.this.create(image, source, environment, CacheMode.READ_ONLY_SEED,
                    executionId, stepId, role);
        }

        @Override public void close() {
            closed = true;
            activeSteps.remove(key);
        }
    }

    private Workload createTrusted(String image, Path source, BuildEnvironment environment,
                                   UUID executionId, String stepId) {
        ensureWritableCache(image, environment, workloadUser(source), executionId, stepId);
        return create(image, source, environment, CacheMode.TRUSTED_WRITABLE, executionId, stepId, "baseline");
    }
    private Workload create(String image, Path source, BuildEnvironment environment, CacheMode mode,
                            UUID executionId, String stepId, String role) {
        String name = "factory-dev-" + UUID.randomUUID();
        String user = workloadUser(source);
        Processes.run(null, createCommand(image, name, user, environment, mode,
                executionId, stepId, role), 120).requireSuccess();
        var workload = new Workload(name, environment.toolchain());
        try {
            Processes.run(null, List.of("docker", "start", name), 60).requireSuccess();
            if (mode == CacheMode.READ_ONLY_SEED) seedCache(name, environment);
            workload.copy(source.resolve(".").toString(), "/workspace");
            // Docker creates WORKDIR as root even when the workload runs as the
            // trusted checkout UID/GID. Allow that user to create build output and
            // new source files at the workspace root; the container remains private.
            Processes.run(null, List.of("docker", "exec", "--user", "0:0", name,
                    "chmod", "0777", "/workspace"), 30).requireSuccess();
            return workload;
        } catch (RuntimeException e) { workload.close(); throw e; }
    }

    private static void ensureWritableCache(String image, BuildEnvironment environment, String user,
                                            UUID executionId, String stepId) {
        String volume = environment.cacheVolume();
        // This short-lived Factory-controlled helper touches only the configured
        // Factory-owned dependency cache volume, before a trusted baseline mounts it writable.
        // The root directory alone is insufficient: cached artifacts and nested
        // directories can belong to a previous UID and block the checkout user.
        Processes.run(null, List.of("docker", "run", "--rm", "--user", "0:0",
                "--label", "factory.dev-owned=true", "--label", "factory.dev-execution=" + executionId,
                "--label", "factory.dev-step=" + stepId, "--label", "factory.dev-role=baseline-cache",
                "--entrypoint", "/bin/sh",
                "--mount", "type=volume,source=" + volume + ",target=/cache", image,
                "-c", writableCacheScript(environment.toolchain(), "/cache"), "--", user), 120).requireSuccess();
    }

    /**
     * Yarn's {@code --mutex} lock directory sits in the cache root and vanishes whenever an install ends, which
     * fails a recursive walk of the root while another build installs; only yarn's version directory is walked.
     */
    static String writableCacheScript(Toolchain toolchain, String cache) {
        return switch (toolchain) {
            case MAVEN -> "chown -R \"$1\" " + cache + " && chmod -R u+rwX " + cache;
            case YARN -> "chown \"$1\" " + cache + " && chmod u+rwX " + cache + " && for d in " + cache
                    + "/v[0-9]*; do [ -e \"$d\" ] || continue; chown -R \"$1\" \"$d\" && chmod -R u+rwX \"$d\" || exit 1; done";
        };
    }

    /** The same lock directory would make {@code cp -a} of the whole yarn cache root fail, so only the version directory is copied. */
    static String seedScript(Toolchain toolchain, String seed, String cache) {
        String copy = switch (toolchain) {
            case MAVEN -> "cp -a " + seed + "/. " + cache + "/";
            case YARN -> "for d in " + seed + "/v[0-9]*; do [ -e \"$d\" ] || continue; cp -a \"$d\" " + cache + "/ || exit 1; done";
        };
        return "mkdir -p " + cache + " && " + copy;
    }

    private void seedCache(String name, BuildEnvironment environment) {
        Toolchain toolchain = environment.toolchain();
        String cache = cachePath(toolchain);
        try {
            Processes.run(null, List.of("docker", "exec", name, "/bin/sh", "-c",
                    seedScript(toolchain, seedPath(toolchain), cache)), seedTimeoutSeconds)
                    .requireSuccess();
        } catch (IllegalStateException e) {
            throw new CacheSeedException(seedFailure(seedTimeoutSeconds, environment.cacheVolume(),
                    cacheSizeKilobytes(name, seedPath(toolchain)), e.getMessage()), e);
        }
    }

    /** The seed copy could not finish; an environment problem, never a candidate or model failure. */
    public static final class CacheSeedException extends IllegalStateException {
        public CacheSeedException(String message, Throwable cause) { super(message, cause); }
    }

    /** Best effort: the size is only there to explain the failure, so a failing measurement is left out. */
    private static long cacheSizeKilobytes(String name, String seed) {
        try {
            var result = Processes.run(null, List.of("docker", "exec", name, "du", "-sk", seed), 60);
            if (result.exitCode() == 0) return parseDuKilobytes(result.output());
            log.warn("Could not measure the seed volume (exit {}): {}", result.exitCode(), result.diagnostics());
        } catch (RuntimeException e) {
            log.warn("Could not measure the seed volume: {}", e.toString());
        }
        return -1;
    }

    /** First field of {@code du -sk} output, which separates size and path with a tab. */
    static long parseDuKilobytes(String output) {
        return Long.parseLong(output.strip().split("\\s+")[0]);
    }

    static String seedFailure(int timeoutSeconds, String volume, long sizeKilobytes, String cause) {
        String size = sizeKilobytes < 0 ? "unknown size"
                : sizeKilobytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f GB", sizeKilobytes / (1024.0 * 1024))
                : sizeKilobytes / 1024 + " MB";
        String shortCause = cause == null ? "" : cause.substring(0, Math.min(300, cause.length()));
        return "Copying the dependency cache volume '" + volume + "' (" + size + ") into a new workload failed (limit "
                + timeoutSeconds + " s): " + shortCause + ". Raise factory.dev-factory.runtime.seed-timeout-seconds"
                + " (FACTORY_DEV_FACTORY_SEED_TIMEOUT_SECONDS) or reduce the cache.";
    }

    static List<String> createCommand(String image, String name, String user, BuildEnvironment environment,
                                      CacheMode mode, UUID executionId, String stepId, String role) {
        var command = new ArrayList<>(List.of("docker", "create", "--name", name,
                "--label", "factory.dev-owned=true",
                "--label", "factory.dev-execution=" + executionId,
                "--label", "factory.dev-step=" + stepId,
                "--label", "factory.dev-role=" + role,
                "--pids-limit", "512", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", user, "--env", "HOME=/tmp/factory-home"));
        for (String variable : variables(environment.toolchain())) command.addAll(List.of("--env", variable));
        command.addAll(List.of("--workdir", "/workspace"));
        if (mode == CacheMode.TRUSTED_WRITABLE) {
            command.addAll(List.of("--mount", "type=volume,source=" + environment.cacheVolume()
                    + ",target=" + cachePath(environment.toolchain())));
        } else if (mode == CacheMode.READ_ONLY_SEED) {
            command.addAll(List.of("--mount", "type=volume,source=" + environment.cacheVolume()
                    + ",target=" + seedPath(environment.toolchain()) + ",readonly"));
        }
        command.addAll(List.of("--entrypoint", "/bin/sh", image, "-c", startupCommand(environment, mode)));
        return List.copyOf(command);
    }

    private static List<String> variables(Toolchain toolchain) {
        return switch (toolchain) {
            case MAVEN -> List.of("MAVEN_OPTS=-Dmaven.repo.local=" + MAVEN_REPOSITORY);
            case YARN -> List.of("YARN_CACHE_FOLDER=" + YARN_CACHE, "CI=true",
                    "JEST_JUNIT_OUTPUT_DIR=artifacts/jest-junit", "JEST_JUNIT_REPORT_TEST_SUITE_ERRORS=true");
        };
    }

    private static String cachePath(Toolchain toolchain) {
        return switch (toolchain) {
            case MAVEN -> MAVEN_REPOSITORY;
            case YARN -> YARN_CACHE;
        };
    }

    private static String seedPath(Toolchain toolchain) {
        return switch (toolchain) {
            case MAVEN -> MAVEN_SEED;
            case YARN -> YARN_SEED;
        };
    }

    private static String startupCommand(BuildEnvironment environment, CacheMode mode) {
        String prepare = switch (environment.toolchain()) {
            case MAVEN -> mode == CacheMode.TRUSTED_WRITABLE
                    ? "mkdir -p " + MAVEN_REPOSITORY : "mkdir -p /tmp/factory-home";
            case YARN -> "mkdir -p " + YARN_CACHE + "; printf '\"@folio:registry\" \"%s\"\\n' '"
                    + environment.npmRegistry() + "' > /tmp/factory-home/.yarnrc";
        };
        return prepare + "; exec sleep infinity";
    }

    enum CacheMode { NONE, TRUSTED_WRITABLE, READ_ONLY_SEED }

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
        private final Toolchain toolchain;
        private boolean stopped;
        private Workload(String name, Toolchain toolchain) { this.name = name; this.toolchain = toolchain; }
        public String name() { return name; }
        public void copy(String local, String destination) {
            Processes.run(null, List.of("docker", "cp", local, name + ":" + destination), 120).requireSuccess();
        }
        /**
         * Places the pinned lockfile at /workspace/yarn.lock, owned by the workload user: {@code docker cp}
         * would make it root-owned and a later non-frozen install could not rewrite it.
         */
        public void seedLockfile(PinnedLockfile lockfile) {
            Path staged = null;
            try {
                staged = java.nio.file.Files.createTempFile("factory-lockfile-", ".lock");
                java.nio.file.Files.writeString(staged, lockfile.content());
                copy(staged.toString(), STAGED_LOCKFILE);
                Processes.run(null, List.of("docker", "exec", "--workdir", "/workspace", name, "cp",
                        STAGED_LOCKFILE, PinnedLockfile.FILE), 30).requireSuccess();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot stage the pinned lockfile", e);
            } finally {
                if (staged != null) try { java.nio.file.Files.deleteIfExists(staged); } catch (java.io.IOException ignored) { }
            }
        }
        /** The lockfile yarn left in /workspace, if any; rejects one that is not usable text within the size limit. */
        public Optional<PinnedLockfile> readLockfile() {
            // Code in the workload decides what sits at this path, so only a plain file is read.
            boolean link = execute(List.of("test", "-L", PinnedLockfile.FILE), 30).exitCode() == 0;
            if (!link && execute(List.of("test", "-e", PinnedLockfile.FILE), 30).exitCode() != 0) return Optional.empty();
            if (link || execute(List.of("test", "-f", PinnedLockfile.FILE), 30).exitCode() != 0)
                throw new IllegalStateException("yarn.lock is not a regular file");
            Path staged = null;
            try {
                staged = java.nio.file.Files.createTempFile("factory-lockfile-", ".lock");
                Processes.run(null, List.of("docker", "cp", name + ":/workspace/" + PinnedLockfile.FILE,
                        staged.toString()), 60).requireSuccess();
                if (!java.nio.file.Files.isRegularFile(staged, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalStateException("yarn.lock is not a regular file");
                if (java.nio.file.Files.size(staged) > PinnedLockfile.MAX_BYTES)
                    throw new IllegalStateException("yarn.lock exceeds " + PinnedLockfile.MAX_BYTES / (1024 * 1024) + " MiB");
                return Optional.of(PinnedLockfile.of(java.nio.file.Files.readAllBytes(staged)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read the generated yarn.lock", e);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("The generated yarn.lock is unusable: " + e.getMessage(), e);
            } finally {
                if (staged != null) try { java.nio.file.Files.deleteIfExists(staged); } catch (java.io.IOException ignored) { }
            }
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
        /** For the plan's own build and test command: a verbose suite is not a failure, so only the output tail is kept. */
        public Processes.Result executeKeepingTail(List<String> argv, int timeout, int tailLimit,
                                                   java.util.function.Consumer<String> stdoutLine) {
            if (stopped) throw new IllegalStateException("Workload is stopped");
            var command = new ArrayList<>(List.of("docker", "exec", "--workdir", "/workspace", name));
            command.addAll(argv);
            return Processes.runKeepingTail(null, command, timeout, java.util.Map.of(), tailLimit, stdoutLine);
        }
        /** Stops the workload for export; yarn's installed dependencies are moved out of /workspace first. */
        public void stop() {
            if (!stopped) {
                if (toolchain == Toolchain.YARN) {
                    Processes.run(null, List.of("docker", "exec", "--workdir", "/workspace", name, "/bin/sh", "-c",
                            "if [ -e node_modules ] || [ -L node_modules ]; then mv node_modules "
                                    + PARKED_NODE_MODULES + "; fi"), 120);
                }
                Processes.run(null, List.of("docker", "stop", "--time", "2", name), 30).requireSuccess();
            }
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
