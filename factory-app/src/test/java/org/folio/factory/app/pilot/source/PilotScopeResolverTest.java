package org.folio.factory.app.pilot.source;

import org.folio.factory.connectors.jira.JiraSourceConnector;
import org.folio.factory.connectors.jira.JiraSourceModels.IssuePage;
import org.folio.factory.connectors.jira.JiraSourceModels.ResolvedSource;
import org.folio.factory.connectors.jira.JiraSourceModels.SourceReference;
import org.folio.factory.connectors.jira.JiraSourceModels.Sprint;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PilotScopeResolverTest {

    private static final String BASE_QUERY =
            "project in (MGRAPPS, MGRENTITLE) AND labels = \"team-ready\" ORDER BY created DESC";
    private static final String QUOTED_AND_NESTED_BASE =
            "summary ~ \"fix ORDER BY nothing\" AND (component = \"core (api)\" OR component = backend)"
                    + " ORDER BY key ASC";

    private final PilotScopeResolver resolver = new PilotScopeResolver(resolvingFake());

    @Test
    void composesSprintStatusesAndParenthesizedBaseQueryBeforeOrderBy() {
        PilotScopeResolver.PilotScope scope = resolver.resolve(
                properties("981", "4711", List.of("To Do"), null, null, null));

        assertThat(scope.effectiveQuery())
                .contains("sprint", "status")
                .startsWith("sprint = 4711 AND status in (\"To Do\")")
                .contains("AND (project in (MGRAPPS, MGRENTITLE) AND labels = \"team-ready\")")
                .endsWith("ORDER BY created DESC");
        assertThat(scope.source().filterId()).isEqualTo("981");
        assertThat(scope.sprint()).isEqualTo(new Sprint("4711", "Sprint 12"));
        assertThat(scope.authorityFingerprint()).isNotBlank();
    }

    @Test
    void blankOptionalFieldsAddNoRestrictions() {
        PilotScopeResolver.PilotScope scope = scope(
                "project in (MGRAPPS, MGRENTITLE) ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), null, null, null));

        assertThat(scope.effectiveQuery())
                .contains("sprint", "status")
                .doesNotContain("labels =", "assignee =")
                .doesNotContain(" AND project in", " AND labels =");
    }

    @Test
    void configuredRestrictionsAreAppendedAfterTheBaseQuery() {
        PilotScopeResolver.PilotScope scope = scope(
                "project in (MGRAPPS, MGRENTITLE) ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), "john.smith",
                        List.of("MGRENTITLE", "MGRAPPS"), "ai-factory"));

        assertThat(scope.effectiveQuery())
                .contains(" AND assignee = \"john.smith\"")
                .contains(" AND project in (\"MGRAPPS\", \"MGRENTITLE\")")
                .contains(" AND labels = \"ai-factory\"")
                .endsWith("ORDER BY created DESC");
    }

    @Test
    void assigneeFunctionFormStaysUnquoted() {
        PilotScopeResolver.PilotScope scope = scope(
                "project = MGRAPPS ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), "currentUser()", null, null));

        assertThat(scope.effectiveQuery()).contains(" AND assignee = currentUser()");
    }

    @Test
    void quotesInConfiguredValuesAreEscaped() {
        PilotScopeResolver.PilotScope scope = scope(
                "project = MGRAPPS ORDER BY created DESC",
                properties("981", "4711", List.of("Ready for Dev"), null, null, "say \"hi\""));

        assertThat(scope.effectiveQuery())
                .contains("status in (\"Ready for Dev\")")
                .contains(" AND labels = \"say \\\"hi\\\"\"");
    }

    @Test
    void inheritedLabelClausesRemainInForce() {
        PilotScopeResolver.PilotScope scope = scope(
                "project = MGRAPPS AND labels = \"team-ready\" ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), null, null, null));

        assertThat(scope.effectiveQuery())
                .contains("(project = MGRAPPS AND labels = \"team-ready\")")
                .endsWith("ORDER BY created DESC");
    }

    @Test
    void orderByIsOnlyDetectedAtTopLevelOutsideQuotesAndParens() {
        PilotScopeResolver.PilotScope scope = scope(
                QUOTED_AND_NESTED_BASE,
                properties("981", "4711", List.of("To Do"), null, null, null));

        assertThat(scope.effectiveQuery())
                .contains("(summary ~ \"fix ORDER BY nothing\""
                        + " AND (component = \"core (api)\" OR component = backend))")
                .endsWith("ORDER BY key ASC");
    }

    @Test
    void fingerprintIsOrderInsensitiveButAuthoritySensitive() {
        String base = "project in (MGRAPPS, MGRENTITLE) ORDER BY created DESC";
        PilotSourceProperties first = properties("981", "4711",
                List.of("To Do", "Ready for Dev"), "john.smith", List.of("MGRENTITLE", "MGRAPPS"), "ai-factory");
        PilotSourceProperties reordered = properties("981", "4711",
                List.of("Ready for Dev", "To Do"), "john.smith", List.of("MGRAPPS", "MGRENTITLE"), "ai-factory");

        assertThat(scope(base, first).authorityFingerprint())
                .isEqualTo(scope(base, reordered).authorityFingerprint());

        String reference = scope(base, properties("981", "4711", List.of("To Do"), null, null, null))
                .authorityFingerprint();
        assertThat(scope(base, properties("981", "5112", List.of("To Do"), null, null, null))
                .authorityFingerprint()).isNotEqualTo(reference);
        assertThat(scope(base, properties("981", "4711", List.of("Ready for Dev"), null, null, null))
                .authorityFingerprint()).isNotEqualTo(reference);
        assertThat(scope(base, properties("981", "4711", List.of("To Do"), "john.smith", null, null))
                .authorityFingerprint()).isNotEqualTo(reference);
        assertThat(scope(base, properties("981", "4711", List.of("To Do"), null, null, "ai-factory"))
                .authorityFingerprint()).isNotEqualTo(reference);
        assertThat(scope(base, properties("981", "4711", List.of("To Do"), null, List.of("MGRAPPS"), null))
                .authorityFingerprint()).isNotEqualTo(reference);
        assertThat(scope("project in (MGRAPPS) ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), null, null, null))
                .authorityFingerprint()).isNotEqualTo(reference);
    }

    @Test
    void fingerprintChangesWhenSourceIdentityChanges() {
        PilotSourceProperties properties = properties("981", "4711", List.of("To Do"), null, null, null);
        ResolvedSource filterA = new ResolvedSource("981", "Filter A",
                URI.create("https://jira.example.org/issues/?filter=981"), "981", "project = MGRAPPS");
        ResolvedSource filterB = new ResolvedSource("982", "Filter B",
                URI.create("https://jira.example.org/issues/?filter=982"), "982", "project = MGRAPPS");
        PilotScopeResolver pure = new PilotScopeResolver(noCalls());

        assertThat(pure.resolve(properties, filterA, new Sprint("4711", "Sprint 12")).authorityFingerprint())
                .isNotEqualTo(
                        pure.resolve(properties, filterB, new Sprint("4711", "Sprint 12")).authorityFingerprint());
    }

    @Test
    void orderByOnlyBaseQueryFailsValidation() {
        assertThatThrownBy(() -> scope("ORDER BY created DESC",
                properties("981", "4711", List.of("To Do"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base query has no filter expression");
    }

    @Test
    void invalidScopeFailsClearly() {
        assertThatThrownBy(() -> scope("project = MGRAPPS",
                properties("981", "4711", List.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> scope(" ",
                properties("981", "4711", List.of("To Do"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> resolver.resolve(
                properties(null, "4711", List.of("To Do"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Team source is required");
        assertThatThrownBy(() -> resolver.resolve(
                properties("981", " ", List.of("To Do"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sprint");
    }

    private PilotScopeResolver.PilotScope scope(String baseQuery, PilotSourceProperties properties) {
        ResolvedSource source = new ResolvedSource("981", "Team board filter",
                URI.create("https://jira.example.org/issues/?filter=981"), "981", baseQuery);
        Sprint sprint = new Sprint(properties.sprintId() == null ? "4711" : properties.sprintId(),
                "Sprint 12");
        return new PilotScopeResolver(noCalls())
                .resolve(properties, source, sprint);
    }

    private static PilotSourceProperties properties(String source, String sprintId, List<String> statuses,
                                                    String assignee, List<String> projects, String label) {
        return new PilotSourceProperties(source, sprintId, statuses, assignee, projects, label);
    }

    private static JiraSourceConnector resolvingFake() {
        return new JiraSourceConnector() {
            @Override
            public SourceReference parseSourceReference(String raw) {
                return SourceReference.parse(raw, "https://jira.example.org");
            }

            @Override
            public ResolvedSource resolveSource(SourceReference reference) {
                return new ResolvedSource(reference.id(), "Team board filter",
                        URI.create("https://jira.example.org/issues/?filter=" + reference.id()),
                        reference.id(), BASE_QUERY);
            }

            @Override
            public Sprint getSprint(String sprintId) {
                if (sprintId == null || sprintId.isBlank()) {
                    throw new AssertionError("sprint id must be validated before the connector call");
                }
                return new Sprint(sprintId, "Sprint 12");
            }

            @Override
            public IssuePage searchIssues(String jql, String cursor) {
                throw new AssertionError("scope resolution must not search issues");
            }
        };
    }

    private static JiraSourceConnector noCalls() {
        return new JiraSourceConnector() {
            @Override
            public SourceReference parseSourceReference(String raw) {
                throw new AssertionError("scope composition must not touch the connector");
            }

            @Override
            public ResolvedSource resolveSource(SourceReference reference) {
                throw new AssertionError("scope composition must not touch the connector");
            }

            @Override
            public Sprint getSprint(String sprintId) {
                throw new AssertionError("scope composition must not touch the connector");
            }

            @Override
            public IssuePage searchIssues(String jql, String cursor) {
                throw new AssertionError("scope composition must not touch the connector");
            }
        };
    }
}
