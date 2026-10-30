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
        if (target == null) {
            throw new DeliveryBlockedException(
                    "Missing factory.dev-factory.delivery.targets." + repositoryKey + " configuration");
        }
        target.requireValid();
        return target;
    }

    /** Legacy targets override catalog fields, inheriting missing repository/base values. */
    public Map<String, DeliveryTarget> effectiveTargets(DevFactoryProperties repositories) {
        Map<String, DeliveryTarget> effectiveTargets = new LinkedHashMap<>();
        for (var entry : repositories.repositories().entrySet()) {
            DeliveryTarget catalogTarget = entry.getValue().delivery();
            if (catalogTarget != null) {
                effectiveTargets.put(entry.getKey(), catalogTarget);
            }
        }
        for (var entry : targets.entrySet()) {
            String repositoryKey = entry.getKey();
            DeliveryTarget catalogTarget = effectiveTargets.get(repositoryKey);
            DeliveryTarget effectiveTarget = legacyOverride(catalogTarget, entry.getValue());
            effectiveTargets.put(repositoryKey, effectiveTarget);
        }
        return Map.copyOf(effectiveTargets);
    }

    public DeliveryTarget target(String repositoryKey, DevFactoryProperties repositories) {
        return effectiveTargets(repositories).get(repositoryKey);
    }

    public DeliveryTarget requireTarget(String repositoryKey, DevFactoryProperties repositories) {
        DeliveryTarget target = target(repositoryKey, repositories);
        if (target == null) {
            throw new DeliveryBlockedException(
                    "Missing delivery configuration for repository '" + repositoryKey + "'");
        }
        target.requireValid();
        return target;
    }

    private static DeliveryTarget legacyOverride(DeliveryTarget catalog, DeliveryTarget legacy) {
        if (catalog == null) {
            return legacy;
        }
        String repository = legacy.repository();
        if (repository == null) {
            repository = catalog.repository();
        }
        String baseBranch = legacy.baseBranch();
        if (baseBranch == null) {
            baseBranch = catalog.baseBranch();
        }
        return new DeliveryTarget(repository, baseBranch);
    }
}
