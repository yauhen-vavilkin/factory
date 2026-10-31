package org.folio.factory.app.support;

import org.folio.factory.app.StubLlmConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The whole application on a random port, the shared database and the scripted LLM. The defaults
 * in {@code config/application.yaml} keep the engine stopped; the {@code engine} and
 * {@code task-execution} profiles turn on the engine and the task queue. Classes with the same
 * profiles, properties and mocks share one cached context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({StubLlmConfiguration.class, SharedDatabase.class})
public @interface FactoryIntegrationTest {
}
