package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.registry.FlowDescriptorParser;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR feedback runs only when an execution's flow version equals {@link FeedbackArtifacts#VERSION}, and the
 * engine fails a step whose outputs differ from the descriptor. A change on one side alone silently stops
 * PR feedback, so both sides are checked against the committed descriptor here.
 */
class FeedbackArtifactsTest {

    @Test
    void theCommittedFlowVersionIsTheVersionThatTurnsOnPrFeedback() throws IOException {
        assertThat(devFactoryFlow().version()).isEqualTo(FeedbackArtifacts.VERSION);
    }

    @Test
    void everyPrFeedbackStepDeclaresExactlyTheFeedbackArtifacts() throws IOException {
        var feedbackSteps = devFactoryFlow().agentChain().stream()
                .filter(step -> step.outputs().contains(FeedbackArtifacts.STATE))
                .toList();

        assertThat(feedbackSteps).extracting(StepDescriptor::stepId)
                .containsExactly("initialize-pr-feedback", FeedbackArtifacts.STEP);
        assertThat(feedbackSteps).allSatisfy(step ->
                assertThat(step.outputs()).as(step.stepId()).containsExactlyElementsOf(FeedbackArtifacts.OUTPUTS));
    }

    private static FlowDescriptor devFactoryFlow() throws IOException {
        try (var in = FeedbackArtifactsTest.class.getClassLoader().getResourceAsStream("flows/dev-factory.yaml")) {
            assertThat(in).as("flows/dev-factory.yaml on the classpath").isNotNull();
            return new FlowDescriptorParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    "dev-factory.yaml");
        }
    }
}
