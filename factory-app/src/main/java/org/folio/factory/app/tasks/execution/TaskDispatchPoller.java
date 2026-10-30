package org.folio.factory.app.tasks.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances the serial task queue: launches the next queued attempt when the
 * active slot is free, retries dispatches that admission could not start, and
 * moves the queue on after a terminal outcome releases the slot. Best-effort —
 * a refused dispatch only logs and waits for the next tick. Disabled unless
 * task execution processing is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.tasks.execution", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TaskDispatchPoller {

    private final TaskDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${factory.tasks.execution.dispatch-interval-ms:30000}")
    public void poll() {
        dispatcher.dispatchNextQuietly();
    }
}
