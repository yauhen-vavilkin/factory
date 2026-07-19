package org.folio.factory.agents.prompt;

/**
 * Resolves the active prompt text for a worker's prompt slot — either a stored
 * override or the classpath default. Workers depend on this instead of loading
 * classpath prompts directly, so overrides take effect on the next call.
 */
@FunctionalInterface
public interface PromptResolver {

    String resolve(String workerId, String promptName);
}
