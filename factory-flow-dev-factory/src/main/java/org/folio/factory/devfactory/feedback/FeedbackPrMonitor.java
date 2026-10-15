package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.registry.FlowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Pages through waiting PR steps; GitHub calls run only on the dedicated executor. */
public class FeedbackPrMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(FeedbackPrMonitor.class);
    private static final int PAGE_SIZE = 20;
    private final PipelineExecutionRepository executions;
    private final FlowRegistry flows;
    private final FeedbackActions actions;
    private final Executor executor;
    private final Clock clock;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Failure> failures = new ConcurrentHashMap<>();
    private int nextPage;

    private record Failure(int count, Instant nextTry) { }

    public FeedbackPrMonitor(PipelineExecutionRepository executions, FlowRegistry flows,
                             FeedbackActions actions, Executor executor, Clock clock) {
        this.executions = executions; this.flows = flows; this.actions = actions;
        this.executor = executor; this.clock = clock;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public synchronized void poll() {
        var page = page(nextPage);
        if (page.isEmpty() && nextPage != 0) {
            nextPage = 0;
            page = page(0);
        }
        nextPage = page.hasNext() ? page.getNumber() + 1 : 0;
        Instant now = clock.instant();
        for (var execution : page.getContent()) {
            if (!FeedbackArtifacts.VERSION.equals(execution.getFlowVersion())) continue;
            var flow = flows.require(execution.getFlowId());
            int index = execution.getCurrentStepIndex();
            if (!flow.hasStep(index) || !FeedbackArtifacts.STEP.equals(flow.step(index).stepId())) continue;
            UUID id = execution.getId();
            var failure = failures.get(id);
            if (failure != null && now.isBefore(failure.nextTry())) continue;
            if (!inFlight.add(id)) continue;
            try {
                executor.execute(() -> check(id));
            } catch (RejectedExecutionException rejected) {
                inFlight.remove(id);
                LOG.debug("PR monitor executor full; will retry {}", id);
            }
        }
    }

    private org.springframework.data.domain.Page<org.folio.factory.core.domain.PipelineExecution> page(int number) {
        return executions.findByStatusAndFlowId(ExecutionStatus.AWAITING_HITL, "dev-factory",
                PageRequest.of(number, PAGE_SIZE, Sort.by("createdAt", "id")));
    }

    private void check(UUID id) {
        try {
            actions.reconcilePr(id);
            failures.remove(id);
        } catch (RuntimeException error) {
            failures.compute(id, (key, old) -> {
                int count = old == null ? 1 : Math.min(10, old.count() + 1);
                long delay = Math.min(300, 30L << Math.min(4, count - 1));
                return new Failure(count, clock.instant().plusSeconds(delay));
            });
            LOG.warn("PR monitor will retry execution {}: {}", id, error.toString());
        } finally {
            inFlight.remove(id);
        }
    }
}
