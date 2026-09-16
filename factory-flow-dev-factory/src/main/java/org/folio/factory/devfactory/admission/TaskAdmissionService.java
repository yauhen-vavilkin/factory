package org.folio.factory.devfactory.admission;

import java.util.List;
import java.util.UUID;
import org.folio.factory.core.trigger.PipelineRouter;
import org.folio.factory.core.trigger.TriggerEvent;
import org.folio.factory.devfactory.contract.ResolvedIntent;
import org.folio.factory.devfactory.contract.TaskRequest;
import org.folio.factory.devfactory.inbox.FileInboxTrigger;
import org.folio.factory.devfactory.inbox.InboxProperties;
import org.folio.factory.devfactory.resolution.TaskResolutionService;
import org.springframework.dao.TransientDataAccessException;

/**
 * The one admission boundary of the Developer Flow: deterministic resolution,
 * idempotent admission keys and flow selection for every task origin. The file
 * inbox and the Jira intake both go through it, so a task is admitted exactly
 * once per semantic revision plus runKey no matter which surface submitted it.
 *
 * <p>Callers own their own retry semantics: repository and transient database
 * failures propagate as {@link org.folio.factory.devfactory.resolution.RepositoryAccessException}
 * or {@link TransientDataAccessException} (retry later), invalid tasks as
 * {@link IllegalArgumentException} (permanent). A BLOCKED resolution is returned
 * without routing; an empty {@code executionIds} list means no flow subscribed
 * to the resolved task.
 */
public final class TaskAdmissionService {
  public static final String DECISION_EVENT_SUFFIX = ".decision";

  private final TaskResolutionService resolver;
  private final PipelineRouter router;
  private final InboxProperties properties;

  public TaskAdmissionService(TaskResolutionService resolver, PipelineRouter router,
                              InboxProperties properties) {
    this.resolver = resolver;
    this.router = router;
    this.properties = properties;
  }

  /** A resolved task plus the executions its admission created (empty = no flow matched). */
  public record Admission(ResolvedIntent intent, List<UUID> executionIds) {
    public boolean needsDecision() {
      return ResolvedIntent.NEEDS_DECISION.equals(intent.status());
    }
  }

  /**
   * Resolves and admits one task. A task that resolves to NEEDS_DECISION is
   * admitted (so there is an execution to pause and resume) through the
   * decision variant of the inbox event, {@code <eventType>.decision}.
   */
  public Admission admit(TaskRequest task, String triggerSource) {
    ResolvedIntent intent = resolver.resolve(task);
    if ("BLOCKED".equals(intent.status())) {
      return new Admission(intent, List.of());
    }
    boolean needsDecision = ResolvedIntent.NEEDS_DECISION.equals(intent.status());
    String eventType = needsDecision
        ? properties.eventType() + DECISION_EVENT_SUFFIX : properties.eventType();
    TriggerEvent event = TriggerEvent.of(eventType, triggerSource,
        FileInboxTrigger.payloadFor(intent));
    List<UUID> admitted = router.routeAdmitted(event, intent.admissionKey());
    return new Admission(intent, admitted == null ? List.of() : List.copyOf(admitted));
  }
}
