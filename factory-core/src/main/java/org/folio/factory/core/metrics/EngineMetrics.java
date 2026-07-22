package org.folio.factory.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Flow-agnostic engine telemetry. Publishes Micrometer meters for pipeline
 * execution lifecycle, step retries/escalations, per-worker step duration, and
 * connector side-effect outcomes. Lives in the control plane and knows nothing
 * about any specific flow: flow modules (which depend on core) record their own
 * connector outcomes through {@link #connectorOutcome} using plain string tags.
 *
 * <p>Binds to whatever {@link MeterRegistry} the application provides — a
 * {@code PrometheusMeterRegistry} once {@code micrometer-registry-prometheus} is on
 * the classpath. When no registry bean is present (for example control-plane
 * integration tests that do not start actuator) it falls back to a local
 * {@link SimpleMeterRegistry}, so injecting this component never fails.</p>
 *
 * <p>Meters are thread-safe; {@code advance()} runs concurrently on the engine task
 * pool. Tagged meters are (re)built through the fluent builder on every call:
 * Micrometer de-duplicates by name+tags and returns the existing meter, so this
 * allocates no duplicates.</p>
 */
@Component
public class EngineMetrics {

    private final MeterRegistry registry;
    private final Counter executionsStarted;
    private final Counter stepRetries;
    private final Counter stepEscalations;

    @Autowired
    public EngineMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Test-friendly constructor: pass a {@link SimpleMeterRegistry} directly. */
    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.executionsStarted = Counter.builder("factory.executions.started")
                .description("Pipeline executions created, including sub-flow children")
                .register(registry);
        this.stepRetries = Counter.builder("factory.steps.retries")
                .description("Agent step attempts that failed and were rescheduled for a retry")
                .register(registry);
        this.stepEscalations = Counter.builder("factory.steps.escalations")
                .description("Agent steps that exhausted their retry budget and escalated to human review")
                .register(registry);
    }

    /** One pipeline execution has been created and enqueued. */
    public void executionStarted() {
        executionsStarted.increment();
    }

    /**
     * A pipeline execution reached a resolved end state. Called only for the final
     * statuses ({@code completed | rejected | cancelled}) — never for
     * {@code FAILED_ESCALATED}, which is resumable via its escalation review and would
     * double-count a run that later resolves. Escalations are observable through
     * {@code factory.steps.escalations} instead.
     *
     * @param outcome lower-case final status: completed | rejected | cancelled
     */
    public void executionFinished(String outcome) {
        Counter.builder("factory.executions.terminal")
                .description("Pipeline executions that reached a terminal state, tagged by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /** A failed step was scheduled for another attempt. */
    public void stepRetryScheduled() {
        stepRetries.increment();
    }

    /** A step exhausted its retry budget and was escalated to human review. */
    public void stepEscalated() {
        stepEscalations.increment();
    }

    /**
     * Records the wall-clock duration of one agent worker step.
     *
     * @param workerId the worker bean id (becomes the {@code worker} tag)
     * @param success  whether the step produced its declared outputs (the {@code outcome} tag)
     * @param duration elapsed time from dispatch to artifact persistence
     */
    public void recordAgentStep(String workerId, boolean success, Duration duration) {
        Timer.builder("factory.agent.step.duration")
                .description("Wall-clock duration of one agent worker step")
                .tag("worker", workerId)
                .tag("outcome", success ? "success" : "failure")
                // Histogram buckets so Prometheus can compute quantiles — the runbook's
                // p99-vs-lease alert is impossible without them. Cardinality is bounded:
                // a handful of workers x 2 outcomes.
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /**
     * Records LLM token usage a worker reported through its result metrics
     * ({@code promptTokens}/{@code completionTokens} keys). No-op when the step
     * reported no token metrics (deterministic workers, scripted test models).
     */
    public void recordLlmTokens(String workerId, Map<String, Object> metrics) {
        recordTokenCount(workerId, "prompt", metrics.get("promptTokens"));
        recordTokenCount(workerId, "completion", metrics.get("completionTokens"));
    }

    private void recordTokenCount(String workerId, String type, Object count) {
        if (count instanceof Number tokens && tokens.longValue() > 0) {
            Counter.builder("factory.llm.tokens")
                    .description("LLM tokens consumed by agent worker steps, tagged by worker and prompt/completion type")
                    .tag("worker", workerId)
                    .tag("type", type)
                    .register(registry)
                    .increment(tokens.doubleValue());
        }
    }

    /**
     * Records the outcome of one external connector side effect performed by a flow
     * worker. Flow modules depend on core, so they call this directly; the meter
     * stays flow-agnostic because the connector name is only a tag value.
     *
     * @param connector connector name (e.g. github, jira, testrail)
     * @param outcome   done | skipped | failed
     */
    public void connectorOutcome(String connector, String outcome) {
        Counter.builder("factory.connector.actions")
                .description("External connector actions attempted by flow workers, tagged by connector and outcome")
                .tag("connector", connector)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
