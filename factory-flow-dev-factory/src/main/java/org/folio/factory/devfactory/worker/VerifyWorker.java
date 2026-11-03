package org.folio.factory.devfactory.worker;

import lombok.RequiredArgsConstructor;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.devfactory.DevFlowSettings;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.tasks.Stop;
import org.folio.factory.devfactory.tasks.TaskGuard;
import org.folio.factory.devfactory.verification.CandidateVerifier;
import org.folio.factory.devfactory.verification.VerificationReceipt;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists Factory's independent observation of the exact frozen candidate. */
@RequiredArgsConstructor
public class VerifyWorker implements AgentWorker {
    public static final String ID = "dev-verify";
    public static final String RECEIPT = "dev_verification.json";
    public static final String RESULT = "dev_result.json";

    private final CandidateVerifier verifier;
    private final DevFlowSettings settings;
    private final TaskGuard taskGuard;
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
            return notRun("No frozen candidate was available for independent verification",
                    new Stop(node.path("state").asString("DEVELOPMENT_FAILED"),
                            node.path("reason").asString("Development did not produce a candidate")));
        }
        Candidate candidate = json.readValue(content, Candidate.class);
        var stop = taskGuard.stop(context.executionId(), context.stepId(), "verification");
        if (stop.isPresent()) {
            return notRun(stop.get().reason(), stop.get());
        }
        var receipt = verifier.verify(context.executionId(), context.stepId(), candidate,
                settings.repositories().sourceUrl(candidate.repository()),
                taskGuard.capTimeout(context.executionId(), settings.runtime().timeoutSeconds()));
        return new AgentResult(Map.of(
                RECEIPT, json.writeValueAsString(receipt),
                RESULT, json.writeValueAsString(outcome(candidate, receipt))), Map.of());
    }

    private static Map<String, Object> outcome(Candidate candidate, VerificationReceipt receipt) {
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
        return outcome;
    }

    /** No verification ran: the receipt says why, and the result carries the stop. */
    private AgentResult notRun(String receiptReason, Stop stop) {
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("state", "NOT_RUN");
        skipped.put("reason", receiptReason);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("state", stop.state());
        outcome.put("reason", stop.reason());
        return new AgentResult(Map.of(RECEIPT, json.writeValueAsString(skipped),
                RESULT, json.writeValueAsString(outcome)), Map.of());
    }

}
