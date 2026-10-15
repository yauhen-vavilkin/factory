package org.folio.factory.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.folio.factory.app.web.DeveloperPricingProperties;

@SpringBootApplication(scanBasePackages = "org.folio.factory")
@EntityScan(basePackages = "org.folio.factory.core.domain")
@EnableJpaRepositories(basePackages = "org.folio.factory.core")
@EnableScheduling
@EnableConfigurationProperties(DeveloperPricingProperties.class)
public class FactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(FactoryApplication.class, args);
    }
}
