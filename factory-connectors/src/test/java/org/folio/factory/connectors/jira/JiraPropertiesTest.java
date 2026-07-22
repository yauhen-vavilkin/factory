package org.folio.factory.connectors.jira;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JiraPropertiesTest {

    @Test
    void configuredOnlyWithBaseUrlEmailAndToken() {
        assertThat(new JiraProperties("https://jira", "qa@example.org", "token").isConfigured()).isTrue();
    }

    @Test
    void baseUrlAloneIsNotConfigured() {
        assertThat(new JiraProperties("https://jira", "", "").isConfigured()).isFalse();
        assertThat(new JiraProperties("https://jira", null, null).isConfigured()).isFalse();
    }

    @Test
    void anyMissingFieldIsNotConfigured() {
        assertThat(new JiraProperties("", "qa@example.org", "token").isConfigured()).isFalse();
        assertThat(new JiraProperties("https://jira", "", "token").isConfigured()).isFalse();
        assertThat(new JiraProperties("https://jira", "qa@example.org", " ").isConfigured()).isFalse();
    }
}
