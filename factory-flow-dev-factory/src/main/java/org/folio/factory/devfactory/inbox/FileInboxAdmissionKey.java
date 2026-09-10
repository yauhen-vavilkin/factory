package org.folio.factory.devfactory.inbox;

import org.folio.factory.devfactory.contract.CanonicalJson;
import tools.jackson.databind.JsonNode;

/**
 * Admission identity of an inbox task file (T22 R1).
 *
 * <p>Compatibility helper for content-addressed inbox keys. M1 admission uses
 * the canonical semantic TaskRequest hash plus runKey from
 * {@link org.folio.factory.devfactory.resolution.TaskResolutionService}; this
 * helper remains for callers that already hold a normalized JSON payload.
 * Object key insertion order does not affect the hash.
 *
 * <p>Consequences:
 * <ul>
 *   <li>Re-dropping the same content — byte-identical or merely reordered,
 *       re-indented, with comments moved — is a <em>replay</em> of the same
 *       admitted revision under any file name: it must return the already
 *       admitted execution, never a second one.</li>
 *   <li>An <em>intentional new revision</em> changes semantic content or the
 *       explicit runKey used by the M1 resolver.</li>
 * </ul>
 */
public final class FileInboxAdmissionKey {

    public static final String PREFIX = "file.inbox:";

    private FileInboxAdmissionKey() {
    }

    public static String of(JsonNode normalizedPayload) {
        return PREFIX + CanonicalJson.sha256(normalizedPayload);
    }
}
