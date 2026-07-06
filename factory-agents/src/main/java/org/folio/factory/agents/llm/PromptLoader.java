package org.folio.factory.agents.llm;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.+?)}}");

    /**
     * Single-pass substitution: placeholders inside substituted values are never
     * re-expanded, and unknown placeholders are left visible so template/variable
     * mismatches surface instead of silently disappearing.
     */
    public static String render(String template, Map<String, Object> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (variables.containsKey(key)) {
                Object value = variables.get(key);
                replacement = value == null ? "" : value.toString();
            } else {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
