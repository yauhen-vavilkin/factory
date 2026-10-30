package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.runtime.Processes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Trusted Git process mechanics only; callers retain branch and replay policy. */
public final class PublicationGit {
    public static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private PublicationGit() {}

    public static Processes.Result run(
            Path workspace, Map<String, String> environment, String... args) {
        return run(workspace, environment, DEFAULT_TIMEOUT_SECONDS, args);
    }

    public static Processes.Result run(
            Path workspace, Map<String, String> environment, int timeoutSeconds, String... args) {
        var command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "-c",
                                "credential.helper="));
        command.addAll(List.of(args));
        return Processes.run(workspace, command, timeoutSeconds, environment);
    }

    public static Map<String, String> identity(String date, String name, String email) {
        return Map.of(
                "GIT_AUTHOR_NAME",
                name,
                "GIT_AUTHOR_EMAIL",
                email,
                "GIT_COMMITTER_NAME",
                name,
                "GIT_COMMITTER_EMAIL",
                email,
                "GIT_AUTHOR_DATE",
                date,
                "GIT_COMMITTER_DATE",
                date);
    }

    public static Map<String, String> authentication(String scope, String token) {
        String header =
                "Authorization: Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token)
                                                .getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT",
                "1",
                "GIT_CONFIG_KEY_0",
                "http." + scope + ".extraHeader",
                "GIT_CONFIG_VALUE_0",
                header);
    }
}
