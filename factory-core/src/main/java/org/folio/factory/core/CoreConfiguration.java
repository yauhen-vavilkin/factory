package org.folio.factory.core;

import org.folio.factory.core.engine.EngineProperties;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class CoreConfiguration {

    @Bean
    public ThreadPoolTaskExecutor factoryEngineExecutor(EngineProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerThreads());
        executor.setMaxPoolSize(properties.workerThreads());
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("factory-engine-");
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    @ConditionalOnClass(HibernatePropertiesCustomizer.class)
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer(JsonMapper jsonMapper) {
        return properties -> properties.put(AvailableSettings.JSON_FORMAT_MAPPER,
                new Jackson3JsonFormatMapper(jsonMapper));
    }
}
