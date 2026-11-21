package org.folio.factory.app.tasks.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRoutingReasonTest {

    @Test
    void theStoredTextsStayAsTheyWereBecauseTheyArePartOfTheDestinationFingerprint() {
        assertThat(TaskRoutingReason.noRepository("MODX"))
                .isEqualTo("Project MODX is not configured: no repository for it in config/developer-repositories.yaml");
        assertThat(TaskRoutingReason.severalRepositories("MODX", List.of("mod-a", "mod-b")))
                .isEqualTo("Project MODX maps to several repositories: mod-a, mod-b");
    }

    @Test
    void eachIneligibleReasonReadsBackAsItsProblem() {
        assertThat(TaskRoutingReason.problem(TaskRoutingReason.noRepository("MODX")))
                .contains(TaskRoutingReason.Problem.NO_REPOSITORY);
        assertThat(TaskRoutingReason.problem(TaskRoutingReason.severalRepositories("MODX", List.of("a", "b"))))
                .contains(TaskRoutingReason.Problem.SEVERAL_REPOSITORIES);
        assertThat(TaskRoutingReason.problem("repository mod-bar")).isEmpty();
        assertThat(TaskRoutingReason.problem(null)).isEmpty();
    }
}
