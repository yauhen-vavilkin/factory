package org.folio.factory.devfactory.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Operator-owned destinations keyed by the trusted Developer Flow repository key. */
@ConfigurationProperties(prefix = "factory.dev-factory.delivery")
public record DevDeliveryProperties(Map<String, DeliveryTarget> targets, boolean createPullRequest) {
    public DevDeliveryProperties {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public DeliveryTarget requireTarget(String repositoryKey) {
        DeliveryTarget target = targets.get(repositoryKey);
        if (target == null) throw new IllegalStateException(
                "Missing factory.dev-factory.delivery.targets." + repositoryKey + " configuration");
        target.requireAuthorized();
        return target;
    }
}
