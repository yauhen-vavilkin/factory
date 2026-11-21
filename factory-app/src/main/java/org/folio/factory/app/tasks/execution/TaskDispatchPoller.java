package org.folio.factory.app.tasks.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances the serial task queue: re-examines the active attempt, so a terminal
 * execution, an expired budget or an ownership loss resolves into a released
 * slot or a visible held attempt, launches the next queued attempt when the
 * slot is free, and retries dispatches that admission could not start. It runs
 * once at startup and then on its interval. Best-effort — a refused dispatch
 * only logs and waits for the next tick. Disabled unless task execution
 * processing is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "factory.tasks.execution", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TaskDispatchPoller {

    private final TaskDispatcher dispatcher;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        dispatcher.dispatchNextQuietly();
    }

    @Scheduled(fixedDelayString = "${factory.tasks.execution.dispatch-interval-ms:30000}")
    public void poll() {
        dispatcher.dispatchNextQuietly();
    }
}
