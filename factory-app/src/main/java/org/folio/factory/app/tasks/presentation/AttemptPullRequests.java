package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.persistence.TaskAttempt;
import org.folio.factory.core.service.ArtifactStore;
import org.folio.factory.devfactory.worker.DeliveryWorker;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The pull request of an attempt: the one its record names once it is ready, or the one its run already opened
 * while the dispatcher has not closed the attempt yet, also while it waits for inspection. Only a live attempt reads
 * its run's delivery.
 */
@Component
@RequiredArgsConstructor
class AttemptPullRequests {

    private final ArtifactStore artifacts;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * {@code url} is null when the attempt has no pull request; {@code draftReasons} is null when the pull request
     * is ready for review and empty when its reasons cannot be read.
     */
    record PullRequest(String url, List<String> draftReasons) {
        static final PullRequest NONE = new PullRequest(null, null);
    }

    PullRequest of(TaskAttempt attempt) {
        if (attempt.getState() == TaskAttempt.State.PR_READY) {
            return new PullRequest(attempt.getPublicationIdentity(), storedReasons(attempt.getDraftReasons()));
        }
        if (!attempt.getState().isLive() || attempt.getExecutionId() == null) {
            return PullRequest.NONE;
        }
        return artifacts.getLatest(attempt.getExecutionId(), DeliveryWorker.DELIVERY)
                .map(artifact -> delivered(readTree(artifact.getContent())))
                .orElse(PullRequest.NONE);
    }

    private PullRequest delivered(JsonNode delivery) {
        var url = delivery.path("pullRequestUrl").asString("");
        if (!"DELIVERED".equals(delivery.path("state").asString("")) || url.isBlank()) {
            return PullRequest.NONE;
        }
        return new PullRequest(url, delivery.path("pullRequestDraft").asBoolean(false)
                ? reasons(delivery.path("draftReasons")) : null);
    }

    private List<String> storedReasons(String stored) {
        return stored == null ? null : reasons(readTree(stored));
    }

    private static List<String> reasons(JsonNode node) {
        List<String> reasons = new ArrayList<>();
        node.forEach(reason -> reasons.add(reason.asString("")));
        return List.copyOf(reasons);
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }
}
