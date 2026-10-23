package org.folio.factory.app.pilot.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Team source configuration: one board or saved filter, one sprint, required
 * eligible statuses and the optional assignee/project/label narrowings.
 */
@ConfigurationProperties("factory.pilot.source")
public record PilotSourceProperties(
        String source,
        String sprintId,
        List<String> statuses,
        String assignee,
        List<String> projects,
        String label) {

    public PilotSourceProperties {
        source = normalize(source);
        sprintId = normalize(sprintId);
        assignee = normalize(assignee);
        label = normalize(label);
        statuses = normalize(statuses);
        projects = normalize(projects);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
}
