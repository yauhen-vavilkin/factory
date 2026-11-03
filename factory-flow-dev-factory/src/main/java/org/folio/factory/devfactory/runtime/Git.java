package org.folio.factory.devfactory.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Git on the Factory host, without repository hooks, stored credentials or line-ending conversion. */
public final class Git {
    public static final int TIMEOUT_SECONDS = 180;

    private Git() {
    }

    public static Processes.Result run(Path directory, Map<String, String> environment, int timeoutSeconds,
                                       String... args) {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "credential.helper=",
                "-c", "core.autocrlf=false"));
        command.addAll(List.of(args));
        return Processes.run(directory, command, timeoutSeconds, environment, Processes.OUTPUT_LIMIT, null);
    }
}
