package org.folio.factory.agents.llm;

import org.folio.factory.agents.prompt.PromptResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Hands the container's {@link PromptResolver} to every LLM worker bean.
 * Needed because workers are created in flow-module {@code @Bean} factory
 * methods, and Spring does not apply annotation-driven setter injection to
 * factory-method return values.
 */
@Component
public class PromptResolverInjector implements BeanPostProcessor {

    private final ObjectProvider<PromptResolver> promptResolver;

    public PromptResolverInjector(ObjectProvider<PromptResolver> promptResolver) {
        this.promptResolver = promptResolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractLlmAgentWorker worker) {
            PromptResolver resolver = promptResolver.getIfAvailable();
            if (resolver != null) {
                worker.setPromptResolver(resolver);
            }
        }
        return bean;
    }
}
