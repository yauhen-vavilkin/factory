package org.folio.factory.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.factory.core.domain.ExecutionStatus.CANCELLED;
import static org.folio.factory.core.domain.ExecutionStatus.COMPLETED;
import static org.folio.factory.core.domain.ExecutionStatus.FAILED_ESCALATED;
import static org.folio.factory.core.domain.ExecutionStatus.REJECTED;

class ExecutionStatusTest {

    @Test
    void failedEscalatedIsTerminalButNotFinal() {
        assertThat(FAILED_ESCALATED.isTerminal()).isTrue();
        assertThat(FAILED_ESCALATED.isFinal()).isFalse();
    }

    @Test
    void terminalStatusesMatchIsTerminal() {
        assertThat(ExecutionStatus.terminalStatuses())
                .containsExactlyInAnyOrder(COMPLETED, FAILED_ESCALATED, REJECTED, CANCELLED);
    }
}
