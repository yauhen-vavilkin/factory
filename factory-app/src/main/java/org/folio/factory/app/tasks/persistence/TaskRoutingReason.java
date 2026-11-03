package org.folio.factory.app.tasks.persistence;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * The routing-reason forms shared by discovery (producer) and their readers
 * (parsers), so what is stored in {@code task.routing_reason} cannot drift out
 * of the stored text. The text itself must not change: it is part of the
 * destination fingerprint of every attempt.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskRoutingReason {

    public static final String ELIGIBLE_PREFIX = "repository ";
    private static final String NO_REPOSITORY = " is not configured: no repository for it in"
            + " config/developer-repositories.yaml";
    private static final String SEVERAL_REPOSITORIES = " maps to several repositories: ";

    /** Why a task cannot run: its Jira project has no repository, or several. */
    public enum Problem {
        NO_REPOSITORY, SEVERAL_REPOSITORIES
    }

    public static String noRepository(String projectKey) {
        return "Project " + projectKey + NO_REPOSITORY;
    }

    public static String severalRepositories(String projectKey, List<String> repositoryKeys) {
        return "Project " + projectKey + SEVERAL_REPOSITORIES + String.join(", ", repositoryKeys);
    }

    /** The problem an ineligible reason names; empty for an eligible reason. */
    public static Optional<Problem> problem(String routingReason) {
        if (routingReason == null || !routingReason.startsWith("Project ")) {
            return Optional.empty();
        }
        if (routingReason.endsWith(NO_REPOSITORY)) {
            return Optional.of(Problem.NO_REPOSITORY);
        }
        return routingReason.contains(SEVERAL_REPOSITORIES) ? Optional.of(Problem.SEVERAL_REPOSITORIES)
                : Optional.empty();
    }

    /** The repository key of an eligible reason, or {@code null} for any other form. */
    public static String eligibleRepositoryKey(String routingReason) {
        if (routingReason == null) {
            return null;
        }
        String stripped = routingReason.strip();
        if (!stripped.startsWith(ELIGIBLE_PREFIX)) {
            return null;
        }
        String key = stripped.substring(ELIGIBLE_PREFIX.length()).strip();
        return key.isEmpty() ? null : key;
    }
}
