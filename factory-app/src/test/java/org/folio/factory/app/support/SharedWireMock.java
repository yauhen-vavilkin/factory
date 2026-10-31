package org.folio.factory.app.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * One WireMock server per faked connector for the whole test JVM, started on first use and never
 * restarted, so no pooled connection can reach a stopped server or a reused port. A class imports
 * the configuration of each connector it fakes, which points that connector at the server, and
 * resets the server before it stubs, so it sees neither stubs nor requests of another class.
 */
public final class SharedWireMock {

    public static final WireMockServer JIRA = start();
    public static final WireMockServer GITHUB = start();
    public static final WireMockServer TESTRAIL = start();

    private SharedWireMock() {
    }

    private static WireMockServer start() {
        var server = new WireMockServer(0);
        server.start();
        return server;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Jira {

        @Bean
        static DynamicPropertyRegistrar sharedJiraUrl() {
            return registry -> registry.add("factory.connectors.jira.base-url", JIRA::baseUrl);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class GitHub {

        @Bean
        static DynamicPropertyRegistrar sharedGitHubUrl() {
            return registry -> registry.add("factory.connectors.github.base-url", GITHUB::baseUrl);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestRail {

        @Bean
        static DynamicPropertyRegistrar sharedTestRailUrl() {
            return registry -> registry.add("factory.connectors.testrail.base-url", TESTRAIL::baseUrl);
        }
    }
}
