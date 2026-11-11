package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;

import java.util.Map;

/** Writes the first, empty versions of the PR feedback artifacts before the run waits for PR comments. */
public class FeedbackInitializer implements AgentWorker {

    @Override
    public String id() {
        return "dev-pr-initialize";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return new AgentResult(FeedbackArtifacts.initial(), Map.of());
    }
}
