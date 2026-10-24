package org.folio.factory.app.pilot.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances the serial pilot queue: launches the next queued attempt when the
 * active slot is free, retries dispatches that admission could not start, and
 * moves the queue on after a terminal outcome releases the slot. Best-effort —
 * a refused dispatch only logs and waits for the next tick. Disabled unless
 * pilot execution processing is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.pilot.execution", name = "enabled", havingValue = "true")
public class PilotDispatchPoller {

    private final PilotDispatcher dispatcher;

    public PilotDispatchPoller(PilotDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${factory.pilot.execution.dispatch-interval-ms:30000}")
    public void poll() {
        dispatcher.dispatchNextQuietly();
    }
}
