package org.folio.factory.core;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class CoreConfiguration {

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
