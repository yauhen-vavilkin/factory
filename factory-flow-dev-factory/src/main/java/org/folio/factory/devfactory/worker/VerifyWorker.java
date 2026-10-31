package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.tasks.TaskAttemptPolicy;
import org.folio.factory.devfactory.tasks.TaskStops;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists Factory's independent observation of the exact frozen candidate. */
@RequiredArgsConstructor
public class VerifyWorker implements AgentWorker {
    public static final String ID = "dev-verify";
    public static final String RECEIPT = "dev_verification.json";
    public static final String RESULT = "dev_result.json";

    private final CandidateVerifier verifier;
    private final DevFactoryProperties repositories;
    private final TaskAttemptPolicy taskPolicy;
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String content = context.requireInput(DevelopWorker.CANDIDATE).content();
        var node = json.readTree(content);
        if (!"CANDIDATE_UNVERIFIED".equals(node.path("state").asString(""))) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("state", "NOT_RUN");
            skipped.put("reason", "No frozen candidate was available for independent verification");
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("state", node.path("state").asString("DEVELOPMENT_FAILED"));
            outcome.put("reason", node.path("reason").asString("Development did not produce a candidate"));
            return new AgentResult(Map.of(RECEIPT, json.writeValueAsString(skipped),
                    RESULT, json.writeValueAsString(outcome)), Map.of());
        }
        Candidate candidate = json.readValue(content, Candidate.class);
        Stop guard = taskStop(context);
        if (guard != null) {
            return stopped(guard);
        }
        var receipt = verifier.verify(context.executionId(), context.stepId(), candidate,
                repositories.sourceUrl(candidate.repository()), taskPolicy.deadline(context.executionId()));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("state", receipt.result().equals("PASS") ? "VERIFIED" : "VERIFICATION_FAILED");
        outcome.put("repository", candidate.repository());
        outcome.put("baseSha", candidate.baseSha());
        outcome.put("treeSha", candidate.treeSha());
        outcome.put("patchSha256", candidate.patchSha256());
        outcome.put("verificationPlan", receipt.planId());
        outcome.put("exitCode", receipt.exitCode());
        outcome.put("testCount", receipt.testCount());
        outcome.put("surefireReportCount", receipt.surefireReportCount());
        outcome.put("failsafeReportCount", receipt.failsafeReportCount());
        outcome.put("failureKind", receipt.failureKind());
        outcome.put("missingRequiredReports", receipt.missingRequiredReports());
        if (!"PASS".equals(receipt.result())) {
            outcome.put("repair", "NOT_ATTEMPTED");
            outcome.put("repairReason", receipt.failureKind().repairable()
                    ? "Automatic repair is deferred: durable single-attempt execution is not supported"
                    : "Verification failure is not eligible for automatic repair: " + receipt.failureKind());
        }
        outcome.put("failureCount", receipt.failureCount());
        outcome.put("errorCount", receipt.errorCount());
        return new AgentResult(Map.of(
                RECEIPT, json.writeValueAsString(receipt),
                RESULT, json.writeValueAsString(outcome)), Map.of());
    }

    /** A stopped, expired or no-longer-owned task attempt must not verify again. */
    private Stop taskStop(AgentContext context) {
        if (taskPolicy.find(context.executionId()).isEmpty()) {
            return null;
        }
        if (!taskPolicy.ownsActiveSlot(context.executionId())) {
            return stop(context, TaskStops.INSPECTION_STATE,
                    "the task attempt no longer owns the installation's active slot;"
                            + " a stopped, superseded or released attempt must not continue");
        }
        var expired = taskPolicy.deadline(context.executionId())
                .filter(deadline -> deadline.expired(Clock.systemUTC()));
        if (expired.isPresent()) {
            return stop(context, TaskStops.DEADLINE_STATE,
                    "the whole-attempt deadline expired before verification;"
                            + " queue and human-review waiting are excluded from the budget");
        }
        return null;
    }

    private Stop stop(AgentContext context, String state, String reason) {
        taskPolicy.recordStop(context.executionId(), context.stepId(), reason);
        return new Stop(state, reason);
    }

    private AgentResult stopped(Stop stop) {
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("state", "NOT_RUN");
        skipped.put("reason", stop.reason());
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("state", stop.state());
        outcome.put("reason", stop.reason());
        return new AgentResult(Map.of(RECEIPT, json.writeValueAsString(skipped),
                RESULT, json.writeValueAsString(outcome)), Map.of());
    }

    private record Stop(String state, String reason) {}
}
