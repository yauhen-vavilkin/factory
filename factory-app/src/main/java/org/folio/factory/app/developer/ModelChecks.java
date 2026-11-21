package org.folio.factory.app.developer;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight.Check;
import org.folio.factory.app.developer.DeveloperPreflight.Status;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.Model;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Models of the Configuration tab: every entry of config/developer-models.yaml with its own checks, so a
 * person sees which models a run form offers and what each of the others still needs. A FAIL or WARN message
 * says the cause and the fix.
 */
@Component
@RequiredArgsConstructor
public class ModelChecks {

    private final ModelCatalog models;
    private final DeveloperPreflightProbe probe;

    /**
     * One model with its checks; ready means a run form offers it. summary is the one line the page shows for a
     * ready model: default, host, efforts and price.
     */
    public record ModelRow(String id, boolean defaultModel, boolean ready, String model, String api,
                           String endpointOrigin, String authentication, String efforts, String summary,
                           List<Check> checks) {
    }

    /** Every model in file order; empty with Pi, whose model comes from FACTORY_PI_*. */
    public List<ModelRow> rows(boolean claudeCode) {
        if (!claudeCode) {
            return List.of();
        }
        Map<String, Boolean> reachable = new HashMap<>();
        String defaultId = models.defaultModel().map(Model::id).orElse(null);
        return models.all().stream().map(model -> row(model, model.id().equals(defaultId), reachable)).toList();
    }

    private ModelRow row(Model model, boolean defaultModel, Map<String, Boolean> reachable) {
        // The catalog accepts only valid base URLs, so the origin always exists.
        String origin = model.endpointOrigin();
        boolean ready = models.problem(model).isEmpty();
        return new ModelRow(model.id(), defaultModel, ready, model.model(), model.api(), origin,
                model.authentication(), String.join(", ", model.efforts()) + "; default " + model.defaultEffort(),
                summary(model, defaultModel), checks(model, ready, reachable));
    }

    /**
     * Only a model that can run gets the TCP probe: the endpoint of the others does not matter yet. A model whose
     * API Claude Code cannot speak gets no efforts or secret check either: its fix is the API alone.
     */
    private List<Check> checks(Model model, boolean ready, Map<String, Boolean> reachable) {
        var api = apiCheck(model);
        var checks = new ArrayList<>(api.status() == Status.FAIL ? List.of(api)
                : List.of(api, effortsCheck(model), secretCheck(model)));
        if (ready) {
            checks.add(endpointCheck(model.endpointOrigin(), reachable));
        }
        checks.add(priceCheck(model));
        return List.copyOf(checks);
    }

    /**
     * "default · host.docker.internal:4001 · effort low, medium, high, xhigh, max · price from platform.claude.com,
     * 2026-11-03".
     */
    private static String summary(Model model, boolean defaultModel) {
        var parts = new ArrayList<String>();
        if (defaultModel) {
            parts.add("default");
        }
        URI endpoint = URI.create(model.endpointOrigin());
        parts.add(endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort()));
        parts.add("effort " + String.join(", ", model.efforts()));
        var price = model.price();
        parts.add(price == null ? "no price" : "price from " + price.source().getHost() + ", " + price.asOf());
        return String.join(" · ", parts);
    }

    private static Check apiCheck(Model model) {
        return ClaudeCodeCodingRuntime.API.equals(model.api())
                ? new Check("api", Status.PASS, "Claude Code can run this model: it uses the Anthropic Messages API.")
                : new Check("api", Status.FAIL, "This model uses " + apiName(model.api()) + "; Claude Code needs"
                        + " the Anthropic Messages API. Point it at an Anthropic-compatible endpoint, or run it"
                        + " with Pi: set FACTORY_DEV_FACTORY_CODING_RUNTIME to pi, and FACTORY_PI_MODEL and the"
                        + " other FACTORY_PI_* settings to this model.");
    }

    /** The API in words: "the OpenAI Chat Completions API" for openai-completions. */
    private static String apiName(String api) {
        return switch (api) {
            case "openai-completions" -> "the OpenAI Chat Completions API";
            case "openai-responses" -> "the OpenAI Responses API";
            case "google-generative-ai" -> "the Google Gemini API";
            default -> "the " + api + " API";
        };
    }

    private static Check effortsCheck(Model model) {
        var unknown = model.efforts().stream().filter(effort -> !ClaudeCodeCodingRuntime.EFFORTS.contains(effort))
                .toList();
        return unknown.isEmpty()
                ? new Check("efforts", Status.PASS, "Claude Code accepts every level of this model.")
                : new Check("efforts", Status.FAIL, "Claude Code does not accept effort " + String.join(", ", unknown)
                        + ". Remove it from efforts in " + ModelCatalog.FILE + "; Claude Code accepts "
                        + String.join(", ", ClaudeCodeCodingRuntime.EFFORTS) + ".");
    }

    private static Check secretCheck(Model model) {
        return model.hasSecret()
                ? new Check("secret", Status.PASS, model.secretVariable() + " is set.")
                : new Check("secret", Status.FAIL, model.secretVariable() + " is empty. Set it in .env. If"
                        + " docker-compose.yml does not pass it to the factory service yet, add it there. Then run"
                        + " docker compose up -d.");
    }

    private Check endpointCheck(String origin, Map<String, Boolean> reachable) {
        boolean open = reachable.computeIfAbsent(origin, key -> probe.gatewayReachable(URI.create(key)));
        return open
                ? new Check("endpoint", Status.PASS, origin + " is reachable over TCP from Factory; the API itself"
                        + " is not tested.")
                : new Check("endpoint", Status.WARN, "Endpoint not reachable: Factory cannot open a connection to "
                        + origin + ". Runs with this model fail if the coding container cannot reach it either."
                        + " Start the service at that address, or fix base-url in " + ModelCatalog.FILE + ".");
    }

    private static Check priceCheck(Model model) {
        var price = model.price();
        return price == null
                ? new Check("price", Status.WARN, "No price, so the cost of its runs shows as unknown. To see"
                        + " costs, add a price to this model in " + ModelCatalog.FILE + ".")
                : new Check("price", Status.PASS, "Price from " + price.source().getHost() + " as of "
                        + price.asOf() + ", " + price.currency() + ".");
    }
}
