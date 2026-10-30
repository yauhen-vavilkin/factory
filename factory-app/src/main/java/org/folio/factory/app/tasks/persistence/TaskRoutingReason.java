package org.folio.factory.app.tasks.persistence;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The eligible routing-reason form shared by discovery (producer) and the
 * attempt policy (parser), so the admitted repository key stored in
 * {@code task.routing_reason} cannot drift out of the stored text.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskRoutingReason {

    public static final String ELIGIBLE_PREFIX = "repository ";

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
