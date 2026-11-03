package org.folio.factory.devfactory.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** One entry of config/developer-models.yaml as written; {@link #toModel} checks it. */
public record ModelEntry(String id, String api, String baseUrl, String authentication, String secretEnv,
                         String model, List<String> efforts, String defaultEffort, Integer maxOutputTokens,
                         ModelPrice price) {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern EFFORT = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /**
     * The checked model with its secret read through {@code variables}. A mistake throws
     * IllegalArgumentException that names the entry and the field to fix.
     */
    Model toModel(Function<String, String> variables) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(ModelCatalog.FILE + ": every model needs an id of lower-case letters,"
                    + " digits, dots and dashes; found " + (id == null ? "an entry without id" : id) + ".");
        }
        String where = ModelCatalog.FILE + ", model " + id + ": ";
        requireEndpoint(where);
        requireEfforts(where);
        require(maxOutputTokens == null || maxOutputTokens > 0, where + "max-output-tokens must be positive.");
        if (price != null) {
            price.validate(where);
        }
        return new Model(id, api, baseUrl, authentication, secretEnv, variables.apply(secretEnv), model, efforts,
                defaultEffort, maxOutputTokens == null ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens, price);
    }

    private void requireEndpoint(String where) {
        require(PiCodingRuntime.APIS.contains(api), where + "api must be one of: "
                + String.join(", ", PiCodingRuntime.APIS) + ".");
        require(model != null && !model.isBlank(), where + "set model.");
        require("api-key".equals(authentication) || "bearer-token".equals(authentication),
                where + "authentication must be api-key or bearer-token.");
        require(secretEnv != null && VARIABLE.matcher(secretEnv).matches(),
                where + "secret-env must be the name of an environment variable, such as FACTORY_ZAI_API_KEY.");
        try {
            Model.origin(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "base-url must be an HTTP(S) URL without credentials,"
                    + " query or fragment.");
        }
    }

    private void requireEfforts(String where) {
        boolean valid = efforts != null && !efforts.isEmpty()
                && efforts.stream().allMatch(effort -> effort != null && EFFORT.matcher(effort).matches())
                && new HashSet<>(efforts).size() == efforts.size();
        require(valid, where + "efforts must list the model's effort levels once each, such as [low, medium, high].");
        require(efforts.contains(defaultEffort),
                where + "default-effort must be one of its efforts: " + String.join(", ", efforts) + ".");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
