package org.folio.factory.testfactory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "factory.test-factory")
public record TestFactoryProperties(
        Execution execution,
        String targetRepo,
        String baseBranch,
        String jiraTransition,
        Long testrailSectionId) {

    public TestFactoryProperties {
        execution = execution == null ? new Execution(null, null) : execution;
        baseBranch = baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch;
    }

    public record Execution(String baseUrl, String karateJar) {

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank() && karateJar != null && !karateJar.isBlank();
        }
    }
}
