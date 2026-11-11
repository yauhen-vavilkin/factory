package org.folio.factory.app.tasks.presentation;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.tasks.execution.TaskExecutionProperties;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.springframework.stereotype.Component;

/** The time per step and per attempt from the properties the Configuration tab's "Limits for every run" shows. */
@Component
@RequiredArgsConstructor
public class RunLimits {

    private final DevRuntimeProperties runtime;
    private final TaskExecutionProperties execution;

    public AttemptClock.Limits limits() {
        return new AttemptClock.Limits(runtime.timeoutSeconds(), execution.deadline());
    }
}
