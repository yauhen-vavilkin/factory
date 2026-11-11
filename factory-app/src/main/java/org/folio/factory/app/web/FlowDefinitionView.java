package org.folio.factory.app.web;

import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.registry.model.TriggerContract;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The Definition view of a flow: its steps in order, how a run starts, and how the engine retries a step that
 * fails. Built from the descriptor alone, in words, so the page shows no engine enums.
 */
public record FlowDefinitionView(List<Step> steps, List<Trigger> triggers, String retries) {

    private static final Set<String> ACRONYMS = Set.of("pr", "qa", "api", "hitl");

    /**
     * One step, numbered from 1. {@code flow} and {@code flowUrl} name and link the flow a sub-flow step runs;
     * {@code note} holds the instructions of a review step. All three are null for other steps.
     */
    public record Step(int number, String name, String kind, String flow, String flowUrl, String data, String note) {
    }

    /** One way a run starts: the event and the payload it needs. */
    public record Trigger(String event, String condition) {
    }

    static FlowDefinitionView of(FlowDescriptor descriptor) {
        var chain = descriptor.agentChain();
        var steps = IntStream.range(0, chain.size()).mapToObj(index -> step(index + 1, chain.get(index))).toList();
        return new FlowDefinitionView(steps, descriptor.triggers().stream().map(FlowDefinitionView::trigger).toList(),
                retries(descriptor.retryPolicy()));
    }

    /** "Step retries: up to 2, waiting 30 s, then 2 min. …", from the attempts the engine really makes. */
    static String retries(RetryPolicy policy) {
        int retries = policy.maxAttempts() - 1;
        var last = " After " + policy.maxAttempts() + (policy.maxAttempts() == 1 ? " failure" : " failures")
                + " of one step, the run waits for a decision in Reviews.";
        if (retries == 0) {
            return "Step retries: none." + last;
        }
        var waits = IntStream.rangeClosed(1, retries)
                .mapToObj(attempt -> UiFormat.duration(policy.backoffFor(attempt))).toList();
        return "Step retries: up to " + retries + ", waiting " + series(waits) + "." + last;
    }

    private static Step step(int number, StepDescriptor step) {
        return switch (step.type()) {
            case AGENT -> new Step(number, words(step.stepId()), agentKind(step), null, null,
                    names(step.inputs()) + " → " + names(step.outputs()), null);
            case HITL_GATE -> gateStep(number, step.gate());
            case SUB_FLOW -> subFlowStep(number, step.stepId(), step.subFlow());
        };
    }

    private static String agentKind(StepDescriptor step) {
        var config = new TreeMap<>(step.config()).entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue()).toList();
        return "Agent step, worker " + step.workerId() + (config.isEmpty() ? "" : ", " + String.join(", ", config));
    }

    private static Step gateStep(int number, HitlGateSpec gate) {
        var data = gate.reviewedArtifacts().isEmpty() ? null : "Reviews " + names(gate.reviewedArtifacts());
        return new Step(number, gate.title(), "Human review", null, null, data, gate.reviewInstructions());
    }

    private static Step subFlowStep(int number, String stepId, SubFlowSpec subFlow) {
        var data = "Passes " + mapping(subFlow.inputMapping()) + "; gets back " + mapping(subFlow.outputMapping());
        return new Step(number, words(stepId), "Sub-flow, runs", subFlow.flowId(),
                UiFormat.flowDefinitionUrl(subFlow.flowId()), data, null);
    }

    private static Trigger trigger(TriggerContract trigger) {
        var condition = trigger.filters().isEmpty() ? "Any payload" : new TreeMap<>(trigger.filters()).entrySet()
                .stream().map(filter -> filter.getKey() + " is " + filter.getValue())
                .collect(Collectors.joining(" and ", "When ", ""));
        return new Trigger(trigger.eventType(), condition);
    }

    /** Artifact names separated by ", "; the trigger payload and an empty list in words. */
    private static String names(List<String> artifacts) {
        if (artifacts.isEmpty()) {
            return "no artifacts";
        }
        return artifacts.stream()
                .map(name -> StepDescriptor.TRIGGER_INPUT.equals(name) ? "the trigger payload" : name)
                .collect(Collectors.joining(", "));
    }

    private static String mapping(Map<String, String> pairs) {
        if (pairs.isEmpty()) {
            return "no artifacts";
        }
        return new TreeMap<>(pairs).entrySet().stream().map(pair -> pair.getKey() + " as " + pair.getValue())
                .collect(Collectors.joining(", "));
    }

    /** "30 s", "30 s, then 2 min", "30 s, 2 min, then 5 min". */
    private static String series(List<String> items) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + ", then " + items.getLast();
    }

    /** "read-task" → "Read task", "pr-follow-up" → "PR follow up". */
    private static String words(String stepId) {
        var words = Arrays.stream(stepId.split("[-_]+"))
                .map(word -> ACRONYMS.contains(word) ? word.toUpperCase() : word).toList();
        var text = String.join(" ", words);
        return text.isEmpty() ? stepId : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
