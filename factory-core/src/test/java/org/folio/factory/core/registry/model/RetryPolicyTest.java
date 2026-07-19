package org.folio.factory.core.registry.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void backoffForClampsAttemptToConfiguredList() {
        RetryPolicy policy = new RetryPolicy(5, List.of(10L, 20L));
        assertThat(policy.backoffFor(0)).isEqualTo(10);
        assertThat(policy.backoffFor(1)).isEqualTo(10);
        assertThat(policy.backoffFor(2)).isEqualTo(20);
        assertThat(policy.backoffFor(99)).isEqualTo(20);
    }

    @Test
    void compactConstructorAppliesDefaults() {
        assertThat(new RetryPolicy(0, null))
                .isEqualTo(new RetryPolicy(3, List.of(30L, 120L, 300L)))
                .isEqualTo(RetryPolicy.DEFAULT);
        assertThat(new RetryPolicy(-1, List.of()).backoffSeconds())
                .containsExactly(30L, 120L, 300L);
    }
}
