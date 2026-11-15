package org.folio.factory.app.web;

import org.folio.factory.app.web.DeveloperRunView.Step;
import org.folio.factory.app.web.DeveloperRunView.StepState;
import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The four step cards of a run page, from the engine steps each product step covers and the artifacts that say
 * how a step ended. {@code current} names the step that works now, null when none does.
 */
record RunSteps(List<Step> cards, String current) {

    private static final Set<String> PREPARED_CANDIDATE_STATES = Set.of("CANDIDATE_UNVERIFIED", "NEEDS_DECISION");
    private static final List<Phase> LEGACY_PHASES = List.of(
            new Phase("Prepare task", 0, 2, Set.of("read-task", "select-repository", "prepare-task")),
            new Phase("Implement changes", 3, 3, Set.of("implement")),
            new Phase("Verify changes", 4, 4, Set.of("verify")),
            new Phase("Create pull request", 5, 5, Set.of("publish")));
    private static final List<Phase> CURRENT_PHASES = List.of(
            new Phase("Prepare task", 0, 2, Set.of("read-task", "select-repository", "prepare-task")),
            new Phase("Implement changes", 3, 5,
                    Set.of("implement", "clarify-implementation", "continue-implementation")),
            new Phase("Verify changes", 6, 6, Set.of("verify")),
            new Phase("Create pull request", 7, 7, Set.of("publish")));
    private static final int IMPLEMENT = 1;
    private static final int VERIFY = 2;
    private static final int PUBLISH = 3;

    private record Phase(String label, int firstStep, int lastStep, Set<String> stepIds) {
    }

    private record Timing(long seconds, boolean seen, boolean running) {
    }

    /** The label of the card that covers this engine step, empty for a step no card covers. */
    static Optional<String> label(String stepId) {
        return CURRENT_PHASES.stream().filter(phase -> phase.stepIds().contains(stepId)).map(Phase::label).findFirst();
    }

    static RunSteps of(PipelineExecution execution, List<AuditEvent> events, Instant now,
                       DeveloperExecutionView.Evidence evidence) {
        var definitions = execution.getFlowVersion().startsWith("0.6.") ? LEGACY_PHASES : CURRENT_PHASES;
        var failedPhase = failedPhase(evidence);
        List<Step> cards = new ArrayList<>(definitions.size());
        String current = null;
        for (int i = 0; i < definitions.size(); i++) {
            var phase = definitions.get(i);
            var timing = timing(phase.stepIds(), events, execution, now);
            var state = state(i, phase, execution, evidence, failedPhase, timing);
            cards.add(card(i, phase, state, new Context(execution, evidence, timing, now)));
            if (state == StepState.CURRENT && !execution.getStatus().isTerminal()) {
                current = phase.label();
            }
        }
        return new RunSteps(List.copyOf(cards), current);
    }

    private record Context(PipelineExecution execution, DeveloperExecutionView.Evidence evidence, Timing timing,
                           Instant now) {
    }

    private static StepState state(int index, Phase phase, PipelineExecution execution,
                                   DeveloperExecutionView.Evidence evidence, int failedPhase, Timing timing) {
        if (notRun(index, evidence)) {
            return StepState.PENDING;
        }
        if (index == IMPLEMENT && stoppedWithQuestion(evidence)) {
            return StepState.QUESTION;
        }
        if (failedPhase >= 0) {
            return index == failedPhase ? StepState.FAILED : index > failedPhase ? StepState.PENDING : StepState.DONE;
        }
        return phaseState(phase, execution, timing.seen());
    }

    private static Step card(int index, Phase phase, StepState state, Context context) {
        var title = index + 1 + " · " + phase.label();
        var timing = context.timing();
        var duration = timing.seen() ? UiFormat.duration(timing.seconds()) : null;
        return switch (state) {
            case PENDING -> new Step(title, state,
                    context.execution().getStatus().isTerminal() ? "Not started" : "Waiting", null, null);
            case DONE -> new Step(title, state, "Passed", doneDetail(index, context.evidence(), duration), null);
            case FAILED -> new Step(title, state, "Failed", duration, null);
            case QUESTION -> new Step(title, state, "Stopped with a question", duration, null);
            case CURRENT -> new Step(title, state, currentLabel(context.execution()), duration,
                    timing.running() ? context.now().minusSeconds(timing.seconds()) : null);
        };
    }

    /** "300 tests" for passed checks and "draft" for a draft pull request say more than the time. */
    private static String doneDetail(int index, DeveloperExecutionView.Evidence evidence, String duration) {
        var verification = evidence.verification();
        if (index == VERIFY && verification.hasNonNull("testCount")) {
            return verification.path("testCount").asString() + " tests";
        }
        if (index == PUBLISH && evidence.delivery().path("pullRequestDraft").asBoolean(false)) {
            return "draft";
        }
        return duration;
    }

    private static String currentLabel(PipelineExecution execution) {
        return switch (execution.getStatus()) {
            case RUNNING -> "Working";
            case PENDING -> "Retry pending";
            case AWAITING_HITL -> "Waiting for review";
            default -> "Stopped";
        };
    }

    /** The agent asked a question and the run stopped before the checks, so implementation did not pass. */
    private static boolean stoppedWithQuestion(DeveloperExecutionView.Evidence evidence) {
        return "NEEDS_DECISION".equals(evidence.codingOutcome().path("status").asString(""))
                && "NOT_RUN".equals(evidence.verification().path("state").asString(""));
    }

    private static int failedPhase(DeveloperExecutionView.Evidence evidence) {
        var preparation = evidence.brief().path("state").asString("");
        if (!preparation.isBlank() && !"INTAKE_READY".equals(preparation)) {
            return 0;
        }
        var candidateState = evidence.candidate().path("state").asString("");
        if ("BASELINE_FAILED".equals(evidence.readiness().path("state").asString(""))
                || !candidateState.isBlank() && !PREPARED_CANDIDATE_STATES.contains(candidateState)) {
            return IMPLEMENT;
        }
        if ("FAIL".equals(evidence.verification().path("result").asString(""))
                || "VERIFICATION_FAILED".equals(evidence.result().path("state").asString(""))) {
            return VERIFY;
        }
        return "DELIVERY_BLOCKED".equals(evidence.delivery().path("state").asString("")) ? PUBLISH : -1;
    }

    /** Verification and delivery record NOT_RUN when the run stopped before them, for example on a question. */
    private static boolean notRun(int phase, DeveloperExecutionView.Evidence evidence) {
        JsonNode outcome = switch (phase) {
            case VERIFY -> evidence.verification();
            case PUBLISH -> evidence.delivery();
            default -> null;
        };
        return outcome != null && "NOT_RUN".equals(outcome.path("state").asString(""));
    }

    private static StepState phaseState(Phase phase, PipelineExecution execution, boolean phaseStarted) {
        int current = execution.getCurrentStepIndex();
        if (execution.getStatus() == ExecutionStatus.PENDING && current == 0 && !phaseStarted) {
            return StepState.PENDING;
        }
        if (current > phase.lastStep()) {
            return StepState.DONE;
        }
        if (current < phase.firstStep()) {
            return StepState.PENDING;
        }
        return execution.getStatus().isTerminal() && execution.getStatus() != ExecutionStatus.COMPLETED
                ? StepState.FAILED : StepState.CURRENT;
    }

    private static Timing timing(Set<String> stepIds, List<AuditEvent> events, PipelineExecution execution,
                                 Instant now) {
        Map<String, Instant> starts = new HashMap<>();
        var total = Duration.ZERO;
        var seen = false;
        for (var event : events) {
            if (event.getStepId() == null || !stepIds.contains(event.getStepId())) {
                continue;
            }
            switch (event.getEventType()) {
                case STEP_STARTED -> {
                    starts.put(event.getStepId(), event.getOccurredAt());
                    seen = true;
                }
                case STEP_COMPLETED, STEP_FAILED ->
                        total = total.plus(closedTime(starts.remove(event.getStepId()), event.getOccurredAt()));
                default -> { }
            }
        }
        var running = !starts.isEmpty() && !execution.getStatus().isTerminal();
        total = total.plus(openTime(starts.values(), timingEnd(execution, now)));
        return new Timing(Math.max(0, total.toSeconds()), seen, running);
    }

    private static Duration closedTime(Instant start, Instant end) {
        return start != null && !end.isBefore(start) ? Duration.between(start, end) : Duration.ZERO;
    }

    private static Duration openTime(Collection<Instant> starts, Instant end) {
        var total = Duration.ZERO;
        for (var start : starts) {
            if (end != null && !end.isBefore(start)) {
                total = total.plus(Duration.between(start, end));
            }
        }
        return total;
    }

    private static Instant timingEnd(PipelineExecution execution, Instant now) {
        if (!execution.getStatus().isTerminal()) {
            return now;
        }
        return execution.getCompletedAt() != null ? execution.getCompletedAt() : execution.getUpdatedAt();
    }
}
