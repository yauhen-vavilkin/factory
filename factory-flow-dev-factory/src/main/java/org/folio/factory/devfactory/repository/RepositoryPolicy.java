package org.folio.factory.devfactory.repository;

import lombok.RequiredArgsConstructor;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.DevFactoryProperties.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a Jira issue to the configured repositories that may implement it.
 * A repository matches when the issue's project is listed and, if the repository
 * restricts components, the issue carries at least one of them. Component matches
 * rank first, then repository key order.
 */
@RequiredArgsConstructor
public class RepositoryPolicy {

    private final DevFactoryProperties properties;

    public List<String> candidates(String projectKey, List<String> issueComponents) {
        return properties.repositories().entrySet().stream()
                .filter(e -> e.getValue().jiraProjects().contains(projectKey))
                .filter(e -> e.getValue().jiraComponents().isEmpty()
                        || e.getValue().jiraComponents().stream().anyMatch(issueComponents::contains))
                .sorted(Comparator.comparing(
                                (Map.Entry<String, Repository> e) -> e.getValue().jiraComponents().isEmpty())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    public Optional<Repository> find(String key) {
        return Optional.ofNullable(properties.repositories().get(key));
    }
}
