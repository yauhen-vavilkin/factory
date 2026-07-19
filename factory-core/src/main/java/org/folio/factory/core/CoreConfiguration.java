package org.folio.factory.core;

import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.limits.LimitsProperties;
import org.folio.factory.core.retention.RetentionProperties;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties({EngineProperties.class, LimitsProperties.class, RetentionProperties.class})
public class CoreConfiguration {

    @Bean
    public ThreadPoolTaskExecutor factoryEngineExecutor(EngineProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerThreads());
        executor.setMaxPoolSize(properties.workerThreads());
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("factory-engine-");
        // Drain in-flight steps on shutdown instead of dropping them. With
        // waitForTasksToCompleteOnShutdown=true the pool defers its real shutdown to
        // bean destruction (Spring's ExecutorConfigurationSupport sets lateShutdown
        // on ContextClosedEvent), so already-dispatched advance() tasks keep running
        // through the lifecycle-stop phase and then get up to shutdownAwaitSeconds to
        // finish. Longer steps are abandoned and the reaper re-queues them after
        // lease-timeout-seconds. The poller stops submitting new work (see
        // ExecutionPoller#shuttingDown), so nothing new should arrive.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownAwaitSeconds());
        // Backpressure instead of rejection: a saturated pool must not throw out
        // of the poller and strand claimed RUNNING executions until the reaper.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
