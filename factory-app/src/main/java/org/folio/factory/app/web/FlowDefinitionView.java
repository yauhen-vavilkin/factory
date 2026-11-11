package org.folio.factory.app.web;

import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.HitlGateSpec;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.SubFlowSpec;
import org.folio.factory.core.registry.model.TriggerContract;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The Definition view of a flow: its steps in order, how a run starts, and how the engine retries a step that
 * fails. Built from the descriptor alone, in words, so the page shows no engine enums; it knows the words of the
 * engine (step config keys, trigger event types), never those of a flow.
 */
public record FlowDefinitionView(List<Step> steps, List<Trigger> triggers, String retries) {

    /** Step config keys the engine reads, in words; any other key shows as "key: value". */
    private static final Map<String, String> CONFIG_WORDS = Map.of(
            "resumable: true", "can pause and go on where it stopped");
    /** The sources of the trigger events, as the webhook adapters name them. */
    private static final Map<String, String> SOURCES = Map.of("jira", "Jira", "github", "GitHub");

    /**
     * One step, numbered from 1. {@code flow} and {@code flowUrl} name and link the flow a sub-flow step runs;
     * {@code note} holds the instructions of a review step. All three are null for other steps.
     */
    public record Step(int number, String name, String kind, String flow, String flowUrl, String data, String note) {
    }

    /** One way a run starts, in words: the event ("Manual start") and what its payload needs. */
    public record Trigger(String event, String condition) {
    }

    static FlowDefinitionView of(FlowDescriptor descriptor) {
        var chain = descriptor.agentChain();
        var steps = IntStream.range(0, chain.size()).mapToObj(index -> step(index + 1, chain.get(index))).toList();
        var required = requiredInputs(descriptor.inputSchema());
        return new FlowDefinitionView(steps,
                descriptor.triggers().stream().map(trigger -> trigger(trigger, required)).toList(),
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
            case AGENT -> new Step(number, UiFormat.inWords(step.stepId()), agentKind(step), null, null,
                    names(step.inputs()) + " → " + names(step.outputs()), null);
            case HITL_GATE -> gateStep(number, step.gate());
            case SUB_FLOW -> subFlowStep(number, step.stepId(), step.subFlow());
        };
    }

    /** "Worker triage-agent, framework: karate": every step of this type runs a worker, not always a model. */
    private static String agentKind(StepDescriptor step) {
        var config = new TreeMap<>(step.config()).entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .map(pair -> CONFIG_WORDS.getOrDefault(pair, pair)).toList();
        return "Worker " + step.workerId() + (config.isEmpty() ? "" : ", " + String.join(", ", config));
    }

    private static Step gateStep(int number, HitlGateSpec gate) {
        var data = gate.reviewedArtifacts().isEmpty() ? null : "Reviews " + names(gate.reviewedArtifacts());
        return new Step(number, gate.title(), "Human review", null, null, data, gate.reviewInstructions());
    }

    private static Step subFlowStep(int number, String stepId, SubFlowSpec subFlow) {
        var data = "Passes " + mapping(subFlow.inputMapping()) + "; gets back " + mapping(subFlow.outputMapping());
        return new Step(number, UiFormat.inWords(stepId), "Sub-flow, runs", subFlow.flowId(),
                UiFormat.flowDefinitionUrl(subFlow.flowId()), data, null);
    }

    /**
     * "Manual start" with "Needs issueKey": the filters of the trigger and the inputs the flow requires of every
     * payload, which the router checks before it starts a run.
     */
    private static Trigger trigger(TriggerContract trigger, List<String> required) {
        var parts = new ArrayList<String>();
        if (!trigger.filters().isEmpty()) {
            parts.add(new TreeMap<>(trigger.filters()).entrySet().stream()
                    .map(filter -> filter.getKey() + " is " + filter.getValue())
                    .collect(Collectors.joining(" and ", "When ", "")));
        }
        if (!required.isEmpty()) {
            parts.add((parts.isEmpty() ? "Needs " : "needs ") + series(required, " and "));
        }
        return new Trigger(event(trigger.eventType()), parts.isEmpty() ? "Any payload" : String.join("; ", parts));
    }

    /** "manual" → "Manual start", "jira.issue.transitioned" → "Jira: issue transitioned"; also the Flows list. */
    static String event(String type) {
        if ("manual".equals(type)) {
            return "Manual start";
        }
        var dot = type.indexOf('.');
        if (dot < 0) {
            return type;
        }
        var source = type.substring(0, dot);
        var what = type.substring(dot + 1).replace('.', ' ').replace('_', ' ');
        return SOURCES.getOrDefault(source, source) + ": " + what;
    }

    private static List<String> requiredInputs(JsonNode schema) {
        var required = schema == null ? null : schema.get("required");
        if (required == null || !required.isArray()) {
            return List.of();
        }
        var names = new ArrayList<String>();
        required.forEach(field -> names.add(field.asString("")));
        return names.stream().filter(name -> !name.isBlank()).toList();
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
        return series(items, ", then ");
    }

    private static String series(List<String> items, String last) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + last + items.getLast();
    }
}
