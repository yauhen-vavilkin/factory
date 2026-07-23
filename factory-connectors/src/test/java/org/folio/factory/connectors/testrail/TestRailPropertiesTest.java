package org.folio.factory.connectors.testrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestRailPropertiesTest {

    @Test
    void configuredOnlyWithAllFieldsAndPositiveProjectId() {
        assertThat(new TestRailProperties("https://testrail", "qa", "key", 7L).isConfigured()).isTrue();
    }

    @Test
    void baseUrlAloneIsNotConfigured() {
        assertThat(new TestRailProperties("https://testrail", "", "", null).isConfigured()).isFalse();
    }

    @Test
    void defaultZeroProjectIdIsNotConfigured() {
        assertThat(new TestRailProperties("https://testrail", "qa", "key", 0L).isConfigured()).isFalse();
        assertThat(new TestRailProperties("https://testrail", "qa", "key", null).isConfigured()).isFalse();
    }

    @Test
    void anyMissingFieldIsNotConfigured() {
        assertThat(new TestRailProperties("", "qa", "key", 7L).isConfigured()).isFalse();
        assertThat(new TestRailProperties("https://testrail", " ", "key", 7L).isConfigured()).isFalse();
        assertThat(new TestRailProperties("https://testrail", "qa", "", 7L).isConfigured()).isFalse();
    }
}
