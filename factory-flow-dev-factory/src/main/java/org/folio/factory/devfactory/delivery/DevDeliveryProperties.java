package org.folio.factory.devfactory.delivery;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Operator-owned destinations keyed by the trusted Developer Flow repository key. */
@ConfigurationProperties(prefix = "factory.dev-factory.delivery")
public record DevDeliveryProperties(Map<String, DeliveryTarget> targets, boolean createPullRequest) {
    public DevDeliveryProperties {
        targets = targets == null ? Map.of() : Map.copyOf(targets);
    }

    public DeliveryTarget requireTarget(String repositoryKey) {
        DeliveryTarget target = targets.get(repositoryKey);
        if (target == null) throw new DeliveryBlockedException(
                "Missing factory.dev-factory.delivery.targets." + repositoryKey + " configuration");
        target.requireAuthorized();
        return target;
    }

    /** Legacy targets override catalog fields, inheriting missing repository/base values. */
    public Map<String, DeliveryTarget> effectiveTargets(DevFactoryProperties repositories) {
        var effective = new LinkedHashMap<String, DeliveryTarget>();
        repositories.repositories().forEach((key, repository) -> {
            if (repository.delivery() != null) effective.put(key, repository.delivery());
        });
        targets.forEach((key, legacy) -> effective.merge(key, legacy, DevDeliveryProperties::legacyOverride));
        return Map.copyOf(effective);
    }

    private static DeliveryTarget legacyOverride(DeliveryTarget catalog, DeliveryTarget legacy) {
        return new DeliveryTarget(
                legacy.repository() == null ? catalog.repository() : legacy.repository(),
                legacy.baseBranch() == null ? catalog.baseBranch() : legacy.baseBranch(),
                legacy.authorized());
    }

    public DeliveryTarget target(String repositoryKey, DevFactoryProperties repositories) {
        return effectiveTargets(repositories).get(repositoryKey);
    }

    public DeliveryTarget requireTarget(String repositoryKey, DevFactoryProperties repositories) {
        var target = target(repositoryKey, repositories);
        if (target == null) throw new DeliveryBlockedException(
                "Missing delivery configuration for repository '" + repositoryKey + "'");
        target.requireAuthorized();
        return target;
    }
}
