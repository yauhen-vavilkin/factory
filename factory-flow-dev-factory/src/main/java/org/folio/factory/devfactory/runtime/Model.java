package org.folio.factory.devfactory.runtime;

import java.net.URI;
import java.util.List;

/**
 * One entry of config/developer-models.yaml with its secret read from the environment: one model at one
 * provider. secret is null when its variable is empty; price is null when the entry has none.
 */
public record Model(String id, String api, String baseUrl, String authentication, String secretVariable,
                    String secret, String model, List<String> efforts, String defaultEffort, int maxOutputTokens,
                    ModelPrice price) {

    public Model {
        efforts = List.copyOf(efforts);
    }

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    public String endpointOrigin() {
        return origin(baseUrl);
    }

    /** scheme://host[:port] of an HTTP(S) URL without credentials, query or fragment. */
    public static String origin(String baseUrl) {
        URI endpoint = URI.create(baseUrl == null ? "" : baseUrl);
        boolean http = "http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme());
        if (!http || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Not an HTTP(S) URL without credentials, query or fragment");
        }
        return endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
    }

    @Override
    public String toString() {
        return "Model[id=" + id + ", model=" + model + "]";
    }
}
