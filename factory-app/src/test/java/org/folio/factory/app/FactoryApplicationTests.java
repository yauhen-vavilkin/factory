package org.folio.factory.app;

import org.folio.factory.app.support.SharedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "factory.engine.enabled=false"})
@Import({StubLlmConfiguration.class, SharedDatabase.class})
@DirtiesContext
class FactoryApplicationTests {

    @Test
    void contextLoadsAndMigrationsApply() {
    }
}
