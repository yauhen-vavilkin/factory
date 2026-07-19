package org.folio.factory.agents.quality;

import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretScanPostProcessorTest {

    private final SecretScanPostProcessor processor = new SecretScanPostProcessor();

    private final StepDescriptor step = new StepDescriptor(
            "test-step", StepType.AGENT, "worker", null, null, null, null, null);

    // Fixture values are split so secret scanners don't flag the source file;
    // the runtime strings still match the production patterns.
    static Stream<Arguments> credentialShapedValues() {
        return Stream.of(
                Arguments.of("AWS access key", "AKIA" + "IOSFODNN7EXAMPLE"),
                Arguments.of("GitHub token", "ghp_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"),
                Arguments.of("Slack token", "xox" + "b-1234567890-abcdefghij"),
                Arguments.of("Private key block", "-----BEGIN RSA " + "PRIVATE KEY-----"),
                Arguments.of("Anthropic API key", "sk-" + "ant-api03-abcdefghijklmnopqrst"),
                Arguments.of("OpenAI API key", "sk-" + "proj-abcdefghijklmnopqrstuvwx"));
    }

    @ParameterizedTest
    @MethodSource("credentialShapedValues")
    void rejectsCredentialShapedOutput(String label, String secret) {
        Map<String, String> outputs = Map.of("scope.md", "# Notes\n\ntoken = " + secret + "\n");

        assertThatThrownBy(() -> processor.process(null, step, outputs))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("scope.md")
                .hasMessageContaining("test-step")
                .hasMessageContaining(label);
    }

    @Test
    void acceptsCleanOutput() {
        Map<String, String> outputs = Map.of(
                "spec.md", "Given the user enters a password\nThen login succeeds with ghp_short");

        assertThatCode(() -> processor.process(null, step, outputs))
                .doesNotThrowAnyException();
    }

    @Test
    void namesTheOffendingOutputArtifact() {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("clean.md", "nothing to see");
        outputs.put("leaky.md", "key: " + "AKIA" + "IOSFODNN7EXAMPLE");

        assertThatThrownBy(() -> processor.process(null, step, outputs))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("leaky.md");
    }
}
