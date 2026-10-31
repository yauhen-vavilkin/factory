package org.folio.factory.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one PostgreSQL container of this test JVM. Every Spring context imports it; the container
 * starts on first use and the Testcontainers reaper removes it when the JVM exits, so closing a
 * context never stops it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedDatabase {

    private static final PostgreSQLContainer<?> POSTGRES = start();

    /** For tests outside Spring that migrate a schema of their own. */
    public static PostgreSQLContainer<?> container() {
        return POSTGRES;
    }

    @Bean
    static DynamicPropertyRegistrar sharedDatabaseProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        };
    }

    /**
     * Empties every table except the ones a context writes only at startup (Flyway history and the
     * flow registry mirror) and restores the installation row that migration V12 seeds. TRUNCATE
     * does not fire the append-only row trigger of audit_event.
     */
    static void clean() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            List<String> tables = dataTables(connection);
            if (tables.isEmpty()) {
                return;
            }
            try (var statement = connection.createStatement()) {
                statement.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
                statement.execute("INSERT INTO task_installation (id, sync_complete) VALUES (1, false)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot clean the shared test database", e);
        }
    }

    private static List<String> dataTables(Connection connection) throws SQLException {
        var tables = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                     + " AND tablename NOT IN ('flyway_schema_history', 'flow_registry')")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private static PostgreSQLContainer<?> start() {
        var container = new PostgreSQLContainer<>("postgres:16");
        container.start();
        return container;
    }
}
