package org.folio.factory.devfactory.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only owned containers, with private writable storage and no host mounts. */
public class DockerWorkloads {
    public Workload create(String image, Path source) {
        String name = "factory-dev-" + UUID.randomUUID();
        String user = workloadUser(source);
        Processes.run(null, List.of("docker", "create", "--name", name, "--label", "factory.dev-owned=true",
                "--cpus", "4", "--memory", "6g", "--pids-limit", "512", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--user", user, "--env", "HOME=/tmp/factory-home",
                "--workdir", "/workspace", "--entrypoint", "/bin/sh", image,
                "-c", "mkdir -p /tmp/factory-home; exec sleep infinity"), 120).requireSuccess();
        var workload = new Workload(name);
        try {
            Processes.run(null, List.of("docker", "start", name), 60).requireSuccess();
            workload.copy(source.resolve(".").toString(), "/workspace");
            return workload;
        } catch (RuntimeException e) { workload.close(); throw e; }
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
            if (stopped) throw new IllegalStateException("Workload is stopped");
            var command = new ArrayList<>(List.of("docker", "exec", "--workdir", "/workspace", name));
            command.addAll(argv);
            return Processes.run(null, command, timeout, java.util.Map.of(), outputLimit);
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
            Processes.run(null, List.of("docker", "rm", "--force", name), 30).requireSuccess();
        }
    }
}
