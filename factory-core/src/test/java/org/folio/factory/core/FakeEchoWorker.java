package org.folio.factory.core;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test fixture: produces every expected output artifact by echoing the inputs.
 */
@Component
public class FakeEchoWorker implements AgentWorker {

    @Override
    public String id() {
        return "echo-worker";
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String echoedInputs = context.inputs().values().stream()
                .map(a -> a.name() + ":" + a.content())
                .collect(Collectors.joining("|"));
        if (context.triggerPayload() != null) {
            echoedInputs = "$trigger:" + context.triggerPayload() + "|" + echoedInputs;
        }
        Map<String, String> outputs = new HashMap<>();
        for (String outputName : context.expectedOutputs()) {
            outputs.put(outputName, "echo[" + outputName + "] <- " + echoedInputs);
        }
        return new AgentResult(outputs, Map.of("fake", true));
    }
}
