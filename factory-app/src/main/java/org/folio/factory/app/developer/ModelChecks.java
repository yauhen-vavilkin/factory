package org.folio.factory.app.developer;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.DeveloperPreflight.Check;
import org.folio.factory.app.developer.DeveloperPreflight.Status;
import org.folio.factory.devfactory.runtime.ClaudeCodeCodingRuntime;
import org.folio.factory.devfactory.runtime.Model;
import org.folio.factory.devfactory.runtime.ModelCatalog;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Models table of Check configuration: every entry of config/developer-models.yaml with its own checks, so a
 * person sees which models a run form offers and what each of the others still needs.
 */
@Component
@RequiredArgsConstructor
public class ModelChecks {

    private final ModelCatalog models;
    private final DeveloperPreflightProbe probe;

    /** One model with its checks; ready means a run form offers it. */
    public record ModelRow(String id, boolean defaultModel, boolean ready, String model, String api,
                           String endpointOrigin, String authentication, String efforts, List<Check> checks) {
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
        List<Check> checks = List.of(apiCheck(model), effortsCheck(model), secretCheck(model),
                endpointCheck(origin, reachable), priceCheck(model));
        return new ModelRow(model.id(), defaultModel, models.problem(model).isEmpty(), model.model(), model.api(),
                origin, model.authentication(), String.join(", ", model.efforts()) + "; default "
                        + model.defaultEffort(), checks);
    }

    private static Check apiCheck(Model model) {
        return ClaudeCodeCodingRuntime.API.equals(model.api())
                ? new Check("api", Status.PASS, model.api() + ": Claude Code can run this model.")
                : new Check("api", Status.FAIL, model.api() + ": Claude Code understands only "
                        + ClaudeCodeCodingRuntime.API + ".");
    }

    private static Check effortsCheck(Model model) {
        var unknown = model.efforts().stream().filter(effort -> !ClaudeCodeCodingRuntime.EFFORTS.contains(effort))
                .toList();
        return unknown.isEmpty()
                ? new Check("efforts", Status.PASS, "Claude Code accepts every level of this model.")
                : new Check("efforts", Status.FAIL, "Claude Code does not accept " + String.join(", ", unknown)
                        + "; it accepts " + String.join(", ", ClaudeCodeCodingRuntime.EFFORTS) + ".");
    }

    private static Check secretCheck(Model model) {
        return model.hasSecret()
                ? new Check("secret", Status.PASS, model.secretVariable() + " is set.")
                : new Check("secret", Status.FAIL, "Set " + model.secretVariable() + " in .env. If docker-compose.yml"
                        + " does not pass it to the factory service yet, add it there. Then run docker compose up -d.");
    }

    private Check endpointCheck(String origin, Map<String, Boolean> reachable) {
        boolean open = reachable.computeIfAbsent(origin, key -> probe.gatewayReachable(URI.create(key)));
        return open
                ? new Check("endpoint", Status.PASS, origin + " is reachable over TCP from Factory; the API itself"
                        + " is not tested.")
                : new Check("endpoint", Status.WARN, origin + " is not reachable over TCP from Factory; check the"
                        + " base URL and the network.");
    }

    private static Check priceCheck(Model model) {
        var price = model.price();
        return price == null
                ? new Check("price", Status.WARN, "No price; runs work, but their cost shows as unknown.")
                : new Check("price", Status.PASS, "Price from " + price.source().getHost() + " as of "
                        + price.asOf() + ", " + price.currency() + ".");
    }
}
