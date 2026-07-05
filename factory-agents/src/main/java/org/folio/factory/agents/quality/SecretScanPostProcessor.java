package org.folio.factory.agents.quality;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.engine.StepPostProcessor;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Framework-level static analysis gate: rejects agent outputs that contain
 * credential-shaped strings before they are persisted or committed anywhere.
 * Patterns are deliberately narrow to avoid false positives on legitimate test
 * fixtures (e.g. the word "password" in a test step).
 */
@Component
public class SecretScanPostProcessor implements StepPostProcessor {

    private static final Map<String, Pattern> SECRET_PATTERNS = Map.of(
            "AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"),
            "GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
            "Slack token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            "Private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            "Anthropic API key", Pattern.compile("sk-ant-[A-Za-z0-9-]{20,}"),
            "OpenAI API key", Pattern.compile("sk-proj-[A-Za-z0-9-]{20,}"));

    @Override
    public void process(FlowDescriptor flow, StepDescriptor step, Map<String, String> outputs) {
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            for (Map.Entry<String, Pattern> secretPattern : SECRET_PATTERNS.entrySet()) {
                if (secretPattern.getValue().matcher(output.getValue()).find()) {
                    throw new AgentExecutionException("Static analysis gate: output artifact '"
                            + output.getKey() + "' of step '" + step.stepId() + "' contains a "
                            + secretPattern.getKey() + " pattern");
                }
            }
        }
    }
}
