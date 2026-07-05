package org.folio.factory.agents.llm;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads worker prompt templates from the classpath and renders
 * {@code {{placeholder}}} variables. Deliberately avoids template engines whose
 * syntax collides with the braces that appear in Markdown/JSON prompt bodies.
 */
public final class PromptLoader {

    private PromptLoader() {
    }

    public static String load(String workerId, String promptName) {
        String location = "prompts/" + workerId + "/" + promptName + ".md";
        try {
            return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load prompt template classpath:" + location, e);
        }
    }

    public static String render(String template, Map<String, Object> variables) {
        String rendered = template;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            rendered = rendered.replace("{{" + variable.getKey() + "}}",
                    variable.getValue() == null ? "" : variable.getValue().toString());
        }
        return rendered;
    }
}
