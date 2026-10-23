package org.folio.factory.app.pilot.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Five-minute discovery callback. It only refreshes the discovered task view;
 * no work is ever admitted here. Disabled unless explicitly enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot", name = "discovery.enabled",
        havingValue = "true", matchIfMissing = false)
public class PilotDiscoveryPoller {

    private static final Logger log = LoggerFactory.getLogger(PilotDiscoveryPoller.class);

    private final PilotDiscoveryService discovery;

    public PilotDiscoveryPoller(PilotDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @Scheduled(fixedDelayString = "${factory.pilot.discovery.poll-interval-ms:300000}")
    public void poll() {
        PilotDiscoveryService.SyncResult result = discovery.sync();
        if (!result.complete()) {
            log.warn("Scheduled pilot discovery incomplete ({} issue(s) read): {}",
                    result.discoveredCount(), result.problem());
        }
    }
}
