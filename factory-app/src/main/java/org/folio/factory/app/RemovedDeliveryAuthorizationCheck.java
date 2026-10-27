package org.folio.factory.app;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Refuses to start while configuration still sets the removed delivery switch
 * {@code authorized}. Binding ignores unknown keys, so an old private catalog with
 * {@code authorized: false} would silently become a live destination, and pull
 * requests would open where the operator believes delivery is off.
 */
@Component
class RemovedDeliveryAuthorizationCheck {

    private static final Pattern REMOVED = Pattern.compile(
            "factory\\.dev-factory\\.(repositories\\.[^.]+\\.delivery|delivery\\.targets\\.[^.]+)\\.authorized");

    RemovedDeliveryAuthorizationCheck(ConfigurableEnvironment environment) {
        List<String> removed = environment.getPropertySources().stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(source -> (EnumerablePropertySource<?>) source)
                .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                .filter(name -> REMOVED.matcher(name).matches())
                .distinct()
                .sorted()
                .toList();
        if (!removed.isEmpty()) {
            throw new IllegalStateException("Delivery has no authorized switch any more: pull requests go to the"
                    + " delivery repository (folio-org by default). Remove " + String.join(", ", removed)
                    + " from your repository catalog and point delivery.repository where pull requests should go.");
        }
    }
}
