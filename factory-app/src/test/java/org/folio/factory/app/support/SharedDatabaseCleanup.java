package org.folio.factory.app.support;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Isolates test classes that share the database: each class starts empty and leaves nothing behind.
 * Its order is below DirtiesContextTestExecutionListener (3000), so after a class it runs once a
 * dirtied context is closed and its engine has stopped writing.
 */
public class SharedDatabaseCleanup implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return 2900;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        SharedDatabase.clean();
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        SharedDatabase.clean();
    }
}
