package org.folio.factory.app.pilot.persistence;

/**
 * The eligible routing-reason form shared by discovery (producer) and the
 * attempt policy (parser), so the admitted repository key stored in
 * {@code pilot_task.routing_reason} cannot drift out of the stored text.
 */
public final class PilotRoutingReason {

    public static final String ELIGIBLE_PREFIX = "repository ";

    private PilotRoutingReason() {
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
