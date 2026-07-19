package org.folio.factory.testfactory.quality;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.engine.StepPostProcessor;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.testfactory.artifact.ScriptBundleCodec;
import org.folio.factory.testfactory.model.ScriptBundle;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Static analysis gate for generated Karate scripts: every generated .feature
 * file must be a structurally plausible Karate feature before it can reach the
 * QA sign-off gate or a repository commit.
 */
@Component
public class KarateSanityPostProcessor implements StepPostProcessor {

    private final ScriptBundleCodec bundleCodec;

    public KarateSanityPostProcessor(ScriptBundleCodec bundleCodec) {
        this.bundleCodec = bundleCodec;
    }

    @Override
    public void process(FlowDescriptor flow, StepDescriptor step, Map<String, String> outputs) {
        String bundleContent = outputs.get("test_scripts.md");
        if (bundleContent == null) {
            return;
        }
        ScriptBundle bundle = bundleCodec.parse(bundleContent);
        if (!"karate".equalsIgnoreCase(bundle.framework())) {
            return;
        }
        for (ScriptBundle.ScriptFile file : bundle.files()) {
            if (!file.path().endsWith(".feature")) {
                throw new AgentExecutionException("Karate sanity check: generated file '"
                        + file.path() + "' is not a .feature file");
            }
            if (!file.content().contains("Feature:") || !file.content().contains("Scenario")) {
                throw new AgentExecutionException("Karate sanity check: '" + file.path()
                        + "' is missing Feature/Scenario structure");
            }
        }
    }
}
