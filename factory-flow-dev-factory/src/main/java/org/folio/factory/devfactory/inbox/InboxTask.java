package org.folio.factory.devfactory.inbox;

import java.util.List;
import java.util.Map;

/**
 * A parsed inbox task file with defaults already applied by
 * {@link InboxTaskFileParser}.
 */
public record InboxTask(String id, String repo, String base, String branch,
                        String goal, List<String> acceptance,
                        Map<String, Object> constraints, String notes) {
}
