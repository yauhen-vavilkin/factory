package org.folio.factory.app.pilot.startup;

import org.folio.factory.app.pilot.Digests;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The non-secret identity of one runtime smoke configuration: the pinned image,
 * literal model, endpoint origin, authentication kind, a hash of the credential
 * (never the credential), the smoke bounds and the instructions hash. The
 * {@link #identity()} hash is the reusable evidence identity: unchanged across
 * an ordinary restart, changed whenever anything that affects inference changes.
 */
public record RuntimeFingerprint(
        String runtime,
        String image,
        String model,
        String endpointOrigin,
        String authentication,
        String credentialIdentity,
        int smokeTurns,
        int smokeTimeoutSeconds,
        String instructionsFingerprint) {

    static final String INVALID_ORIGIN = "unset or invalid";

    /** SHA-256 hex of an identity string; used for instructions and credential identities. */
    public static String identityOf(String value) {
        return Digests.sha256Hex(value);
    }

    public static RuntimeFingerprint of(DevRuntimeProperties.ClaudeCode profile, int smokeTurns,
                                        int smokeTimeoutSeconds, String instructionsFingerprint) {
        return new RuntimeFingerprint("claude-code", profile.image(), profile.model(),
                origin(profile.baseUrl()), profile.authentication(), Digests.sha256Hex(profile.secret()),
                smokeTurns, smokeTimeoutSeconds, instructionsFingerprint);
    }

    public RuntimeFingerprint {
        runtime = nonBlank(runtime, "unset");
        image = nonBlank(image, "unset");
        model = nonBlank(model, "unset");
        endpointOrigin = nonBlank(endpointOrigin, INVALID_ORIGIN);
        authentication = nonBlank(authentication, "unset");
        credentialIdentity = nonBlank(credentialIdentity, "unset");
        instructionsFingerprint = nonBlank(instructionsFingerprint, "unset");
    }

    public String identity() {
        return Digests.sha256Hex(String.join("\n", runtime, image, model, endpointOrigin, authentication,
                credentialIdentity, String.valueOf(smokeTurns), String.valueOf(smokeTimeoutSeconds),
                instructionsFingerprint));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runtime", runtime);
        map.put("image", image);
        map.put("model", model);
        map.put("endpointOrigin", endpointOrigin);
        map.put("authentication", authentication);
        map.put("credentialIdentity", credentialIdentity);
        map.put("smokeTurns", smokeTurns);
        map.put("smokeTimeoutSeconds", smokeTimeoutSeconds);
        map.put("instructionsFingerprint", instructionsFingerprint);
        return Map.copyOf(map);
    }

    private static String origin(String baseUrl) {
        try {
            URI endpoint = URI.create(baseUrl);
            boolean supported = ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                    && endpoint.getHost() != null && endpoint.getUserInfo() == null
                    && endpoint.getRawQuery() == null && endpoint.getFragment() == null;
            if (!supported) {
                return INVALID_ORIGIN;
            }
            int port = endpoint.getPort();
            return new StringBuilder(endpoint.getScheme()).append("://").append(endpoint.getHost())
                    .append(port < 0 ? "" : ":" + port)
                    .toString();
        } catch (IllegalArgumentException e) {
            return INVALID_ORIGIN;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
