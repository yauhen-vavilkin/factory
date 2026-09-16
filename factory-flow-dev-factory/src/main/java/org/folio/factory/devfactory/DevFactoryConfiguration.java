package org.folio.factory.devfactory;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.devfactory.worker.DevSmokeWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Developer Flow plugin wiring. The flow itself is data ({@code flows/dev-factory.yaml});
 * this class only assembles the worker beans the descriptor references.
 */
@Configuration
public class DevFactoryConfiguration {

    @Bean
    public DevSmokeWorker devSmokeWorker(FrontmatterCodec frontmatterCodec) {
        return new DevSmokeWorker(frontmatterCodec);
    }
}
