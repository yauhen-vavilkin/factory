package org.folio.factory.devfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.service.AuditLog;
import org.folio.factory.devfactory.decision.DecisionArtifacts;
import org.folio.factory.devfactory.pilot.PilotExecutionContext;
import org.folio.factory.devfactory.pilot.RecordingPilotAttempts;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;
import org.folio.factory.devfactory.runtime.PiCodingRuntime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test void pilotAmbiguityStopsIntakeWithoutJiraReadOrSeededRecommendation() {
        var jira = mock(JiraConnector.class);
        var audit = mock(AuditLog.class);
        var repositoryPolicy = mock(RepositoryPolicy.class);
        var pilots = new RecordingPilotAttempts(pilotContext("mod-foo"));
        var worker = new IntakeWorker(jira, repositoryPolicy, new FrontmatterCodec(),
                codingRuntime(), audit, pilots);
        when(repositoryPolicy.candidates("MODFOO", List.of("storage")))
                .thenReturn(List.of("mod-foo", "mod-foo-storage"));
        var context = new AgentContext(UUID.randomUUID(), "read-task", Map.of(), null, Map.of(), List.of());

        var result = worker.execute(context);

        var codec = new FrontmatterCodec();
        var intake = codec.parse(result.outputs().get(IntakeWorker.INTAKE)).metadata();
        assertThat(intake.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(intake.path("reason").asString()).isEqualTo("AMBIGUOUS_REPOSITORY_MAPPING");
        assertThat(intake.path("issue_key").asString()).isEqualTo("MODFOO-101");
        var request = DecisionArtifacts.parseRequest(codec, result.outputs().get(DecisionArtifacts.REQUEST));
        assertThat(request.required()).isFalse();
        var answer = DecisionArtifacts.parseAnswer(codec, result.outputs().get(DecisionArtifacts.ANSWER));
        assertThat(answer.choice()).isEqualTo("none");
        assertThat(pilots.stops).singleElement().satisfies(stop -> {
            assertThat(stop).startsWith("read-task: ");
            assertThat(stop).contains("ambiguous");
        });
        verifyNoInteractions(jira);
    }

    @Test void pilotSingleCandidateMapsFromTheApprovedSnapshotWithoutJiraRead() {
        var jira = mock(JiraConnector.class);
        var audit = mock(AuditLog.class);
        var repositoryPolicy = mock(RepositoryPolicy.class);
        var pilots = new RecordingPilotAttempts(pilotContext("mod-foo"));
        var worker = new IntakeWorker(jira, repositoryPolicy, new FrontmatterCodec(),
                codingRuntime(), audit, pilots);
        when(repositoryPolicy.candidates("MODFOO", List.of("storage")))
                .thenReturn(List.of("mod-foo"));
        var context = new AgentContext(UUID.randomUUID(), "read-task", Map.of(), null, Map.of(), List.of());

        var result = worker.execute(context);

        var codec = new FrontmatterCodec();
        var intake = codec.parse(result.outputs().get(IntakeWorker.INTAKE)).metadata();
        assertThat(intake.path("state").asString()).isEqualTo("MAPPED");
        assertThat(intake.path("candidates").get(0).asString()).isEqualTo("mod-foo");
        assertThat(DecisionArtifacts.parseRequest(codec, result.outputs().get(DecisionArtifacts.REQUEST)).required())
                .isFalse();
        assertThat(pilots.stops).isEmpty();
        verify(repositoryPolicy).candidates("MODFOO", List.of("storage"));
        verifyNoInteractions(jira);
    }

    @Test void pilotRoutingAwayFromTheApprovedRepositoryStops() {
        var jira = mock(JiraConnector.class);
        var audit = mock(AuditLog.class);
        var repositoryPolicy = mock(RepositoryPolicy.class);
        var pilots = new RecordingPilotAttempts(pilotContext("mod-foo"));
        var worker = new IntakeWorker(jira, repositoryPolicy, new FrontmatterCodec(),
                codingRuntime(), audit, pilots);
        when(repositoryPolicy.candidates("MODFOO", List.of("storage")))
                .thenReturn(List.of("mod-foo-storage"));
        var context = new AgentContext(UUID.randomUUID(), "read-task", Map.of(), null, Map.of(), List.of());

        var result = worker.execute(context);

        var intake = new FrontmatterCodec().parse(result.outputs().get(IntakeWorker.INTAKE)).metadata();
        assertThat(intake.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(intake.path("reason").asString()).isEqualTo("REPOSITORY_MAPPING_CHANGED");
        assertThat(pilots.stops).singleElement().satisfies(stop -> assertThat(stop).contains("mod-foo"));
        verifyNoInteractions(jira);
    }

    @Test void pilotUnverifiableAdmittedRepositoryStopsInsteadOfTrustingTheReDerivedMapping() {
        var jira = mock(JiraConnector.class);
        var audit = mock(AuditLog.class);
        var repositoryPolicy = mock(RepositoryPolicy.class);
        var pilots = new RecordingPilotAttempts(pilotContext(null));
        var worker = new IntakeWorker(jira, repositoryPolicy, new FrontmatterCodec(),
                codingRuntime(), audit, pilots);
        when(repositoryPolicy.candidates("MODFOO", List.of("storage")))
                .thenReturn(List.of("mod-foo"));
        var context = new AgentContext(UUID.randomUUID(), "read-task", Map.of(), null, Map.of(), List.of());

        var result = worker.execute(context);

        var intake = new FrontmatterCodec().parse(result.outputs().get(IntakeWorker.INTAKE)).metadata();
        assertThat(intake.path("state").asString()).isEqualTo("BLOCKED");
        assertThat(intake.path("reason").asString()).isEqualTo("REPOSITORY_MAPPING_CHANGED");
        assertThat(pilots.stops).singleElement().satisfies(stop ->
                assertThat(stop).contains("cannot be verified"));
        verifyNoInteractions(jira);
    }

    private static PilotExecutionContext pilotContext(String repositoryKey) {
        return new PilotExecutionContext(
                UUID.randomUUID(),
                Map.of("key", "MODFOO-101", "summary", "Summary", "description", "Description",
                        "components", List.of("storage"), "comments", List.of(), "links", List.of()),
                repositoryKey,
                PilotExecutionContext.Kind.INITIAL,
                "input-fingerprint", "destination-fingerprint");
    }

    private static CodingRuntime codingRuntime() {
        CodingRuntime coding = mock(CodingRuntime.class);
        when(coding.identity(any())).thenReturn(Map.of("runtime", "stub", "image", "stub:image"));
        return coding;
    }
}
