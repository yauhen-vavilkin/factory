package org.folio.factory.devfactory;

import org.folio.factory.core.registry.FlowDescriptorParser;
import org.folio.factory.core.registry.FlowValidationException;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.RetryPolicy;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevFactoryFlowDescriptorTest {

    private final FlowDescriptorParser parser = new FlowDescriptorParser();

    @Test
    void loadsTriggerSchemasAndRetryPolicy() {
        FlowDescriptor flow = parser.parse(descriptorYaml(), "flows/dev-factory.yaml");

        assertThat(flow.id()).isEqualTo("dev-factory");
        assertThat(flow.name()).isNotBlank();
        assertThat(flow.version()).isNotBlank();
        assertThat(flow.triggers()).hasSize(1);
        assertThat(flow.triggers().getFirst().eventType()).isEqualTo("file.inbox");
        assertThat(flow.triggers().getFirst().filters()).isEmpty();
        assertThat(textValues(flow.inputSchema().get("required")))
                .containsExactly("taskId", "repoUrl", "baseRevision", "resolvedIntent", "goal");
        assertThat(textValues(flow.outputSchema().get("artifacts")))
                .containsExactly("patch.diff", "report.md", "trajectory.jsonl", "delivery-summary.md");
        assertThat(flow.retryPolicy()).isEqualTo(RetryPolicy.DEFAULT);
    }

    @Test
    void agentChainDeclaresCodingThenFinalize() {
        FlowDescriptor flow = parser.parse(descriptorYaml(), "flows/dev-factory.yaml");

        assertThat(flow.agentChain()).hasSize(2);
        assertThat(flow.agentChain())
                .allSatisfy(step -> assertThat(step.type()).isEqualTo(StepType.AGENT));

        StepDescriptor coding = flow.step(0);
        assertThat(coding.stepId()).isEqualTo("coding");
        assertThat(coding.workerId()).isEqualTo("coding-worker");
        assertThat(coding.inputs()).containsExactly("$trigger");
        assertThat(coding.outputs()).containsExactly("patch.diff", "report.md", "trajectory.jsonl");

        StepDescriptor finalize = flow.step(1);
        assertThat(finalize.stepId()).isEqualTo("finalize");
        assertThat(finalize.workerId()).isEqualTo("dev-factory-finalizer");
        assertThat(finalize.inputs()).containsExactly("report.md", "$trigger");
        assertThat(finalize.outputs()).containsExactly("delivery-summary.md");
    }

    @Test
    void strictnessAppliesToThisDescriptor() {
        String withoutWorkerId = descriptorYaml().lines()
                .filter(line -> !line.equals("    worker_id: coding-worker"))
                .collect(Collectors.joining("\n"));

        assertThatThrownBy(() -> parser.parse(withoutWorkerId, "flows/dev-factory.yaml"))
                .isInstanceOf(FlowValidationException.class)
                .hasMessageContaining("worker_id");
    }

    @Test
    void piFlowDeclaresOneExplicitBoundedRepairAndFullReverification() {
        FlowDescriptor flow = parser.parse(piDescriptorYaml(), "flows/dev-factory-pi.yaml");

        assertThat(flow.agentChain()).extracting(StepDescriptor::stepId)
                .containsExactly("prepare", "coding", "verify", "repair", "reverify", "finalize");
        assertThat(flow.agentChain()).filteredOn(step -> step.workerId().equals("pi-repair-worker"))
                .hasSize(1);
        assertThat(flow.step(3).inputs()).contains("verification.json", "candidate.patch", "candidate.json");
        assertThat(flow.step(3).outputs()).contains("repair.json", "candidate.patch", "candidate.json");
        assertThat(flow.step(4).workerId()).isEqualTo("pi-reverify-worker");
        assertThat(flow.step(4).inputs()).contains("contract.json", "baseline.json", "repair.json");
    }

    @Test
    void routineFlowHasNoHumanGateSoFailuresAndBlockersNeverWaitForAHuman() {
        FlowDescriptor flow = parser.parse(resource("flows/dev-factory-pi.yaml"), "flows/dev-factory-pi.yaml");

        assertThat(flow.agentChain()).noneMatch(step -> step.type() == StepType.HITL_GATE);
    }

    @Test
    void decisionFlowPausesBeforeAnyPiWorkerAndResumesIntoTheSamePiSteps() {
        FlowDescriptor routine = parser.parse(resource("flows/dev-factory-pi.yaml"), "flows/dev-factory-pi.yaml");
        FlowDescriptor flow = parser.parse(resource("flows/dev-factory-pi-decision.yaml"),
                "flows/dev-factory-pi-decision.yaml");

        assertThat(flow.triggers().getFirst().eventType()).isEqualTo("file.inbox.pi.decision");
        assertThat(flow.agentChain()).extracting(StepDescriptor::stepId).containsExactly(
                "decision-request", "decision", "decision-resume",
                "prepare", "coding", "verify", "repair", "reverify", "finalize");
        assertThat(flow.step(1).type()).isEqualTo(StepType.HITL_GATE);
        assertThat(flow.step(1).gate().gateId()).isEqualTo("developer-decision");
        assertThat(flow.step(1).gate().reviewedArtifacts()).contains("decision-request.json");
        assertThat(flow.agentChain().subList(0, 3))
                .noneMatch(step -> step.workerId() != null && step.workerId().startsWith("pi-"));
        assertThat(flow.agentChain().subList(3, 9)).extracting(StepDescriptor::workerId)
                .containsExactlyElementsOf(routine.agentChain().stream().map(StepDescriptor::workerId).toList());
        assertThat(flow.agentChain().subList(3, 9)).allSatisfy(step ->
                assertThat(step.inputs()).contains("task.json"));
    }

    private static String resource(String name) {
        try (InputStream in = DevFactoryFlowDescriptorTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("classpath resource " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String descriptorYaml() {
        try (InputStream in = DevFactoryFlowDescriptorTest.class.getClassLoader()
                .getResourceAsStream("flows/dev-factory.yaml")) {
            assertThat(in).as("classpath resource flows/dev-factory.yaml").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String piDescriptorYaml() {
        try (InputStream in = DevFactoryFlowDescriptorTest.class.getClassLoader()
                .getResourceAsStream("flows/dev-factory-pi.yaml")) {
            assertThat(in).as("classpath resource flows/dev-factory-pi.yaml").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
