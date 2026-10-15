package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IntakeWorkerTest {
    @Test void recordsOnlyDisplayIdentityBeforeReadingJiraEvenWhenIntakeFails() {
        var jira = mock(JiraConnector.class);
        var audit = mock(AuditLog.class);
        var coding = new DevRuntimeProperties.Coding("coding-image", "provider", "model",
                "https://private.example", "openai-completions", "secret-key", "pi", null, null);
        var worker = new IntakeWorker(jira, mock(RepositoryPolicy.class), new FrontmatterCodec(),
                new PiCodingRuntime(coding), audit);
        var context = new AgentContext(UUID.randomUUID(), "read-task", Map.of(),
                JsonMapper.builder().build().readTree("{\"issueKey\":\"TASK-1\"}"), Map.of(), List.of());
        when(jira.getIssue("TASK-1")).thenThrow(new IllegalStateException("Jira unavailable"));

        assertThatThrownBy(() -> worker.execute(context)).isInstanceOf(IllegalStateException.class);

        var order = inOrder(audit, jira);
        order.verify(audit).record(context.executionId(), AuditEventType.RUNTIME_PROGRESS, "read-task", "dev-intake",
                Map.of("activity", "coding_configured", "runtime", "pi", "model", "model",
                        "provider", "provider", "image", "coding-image"));
        order.verify(jira).getIssue("TASK-1");
        verifyNoMoreInteractions(audit);
    }
}
