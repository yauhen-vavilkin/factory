package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.domain.ExecutionStatus;
import org.folio.factory.core.domain.PipelineExecution;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackPrMonitorTest {
    @Test void rotatesTwentyAtATimeAndKeepsGitHubWorkOffThePollingThread() {
        var executions=mock(PipelineExecutionRepository.class);
        var flows=mock(FlowRegistry.class);
        var flow=mock(FlowDescriptor.class);
        var step=mock(StepDescriptor.class);
        when(flows.require("dev-factory")).thenReturn(flow);
        when(flow.hasStep(9)).thenReturn(true);
        when(flow.step(9)).thenReturn(step);
        when(step.stepId()).thenReturn(FeedbackArtifacts.STEP);
        var actions=mock(FeedbackActions.class);
        var waiting=new ArrayList<PipelineExecution>();
        for(int i=0;i<25;i++) {
            var execution=new PipelineExecution("dev-factory",FeedbackArtifacts.VERSION,"{}");
            execution.setStatus(ExecutionStatus.AWAITING_HITL);
            execution.setCurrentStepIndex(9);
            waiting.add(execution);
        }
        when(executions.findByStatusAndFlowId(eq(ExecutionStatus.AWAITING_HITL),eq("dev-factory"),any(Pageable.class)))
                .thenAnswer(invocation->{
                    Pageable pageable=invocation.getArgument(2);
                    int first=(int)pageable.getOffset();
                    return new PageImpl<>(first>=waiting.size()?List.of():waiting.subList(first,Math.min(first+20,waiting.size())),
                            pageable,waiting.size());
                });
        var queued=new ArrayList<Runnable>();
        var monitor=new FeedbackPrMonitor(executions,flows,actions,queued::add,
                Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"),ZoneOffset.UTC));
        monitor.poll();
        assertThat(queued).hasSize(20);
        verifyNoInteractions(actions);
        queued.forEach(Runnable::run);
        monitor.poll();
        assertThat(queued).hasSize(25);
        queued.subList(20,25).forEach(Runnable::run);
        verify(actions,times(1)).reconcilePr(waiting.getFirst().getId());
        verify(actions,times(1)).reconcilePr(waiting.getLast().getId());
    }

    @Test void failedCheckBacksOffWithoutChangingExecution() {
        var executions=mock(PipelineExecutionRepository.class);
        var flows=mock(FlowRegistry.class);
        var flow=mock(FlowDescriptor.class);
        var step=mock(StepDescriptor.class);
        when(flows.require("dev-factory")).thenReturn(flow);
        when(flow.hasStep(9)).thenReturn(true);
        when(flow.step(9)).thenReturn(step);
        when(step.stepId()).thenReturn(FeedbackArtifacts.STEP);
        var execution=new PipelineExecution("dev-factory",FeedbackArtifacts.VERSION,"{}");
        execution.setStatus(ExecutionStatus.AWAITING_HITL);
        execution.setCurrentStepIndex(9);
        when(executions.findByStatusAndFlowId(eq(ExecutionStatus.AWAITING_HITL),eq("dev-factory"),any(Pageable.class)))
                .thenAnswer(invocation->new PageImpl<>(List.of(execution),invocation.getArgument(2),1));
        var actions=mock(FeedbackActions.class);
        doThrow(new IllegalStateException("GitHub unavailable")).when(actions).reconcilePr(execution.getId());
        var monitor=new FeedbackPrMonitor(executions,flows,actions,Runnable::run,
                Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"),ZoneOffset.UTC));
        monitor.poll(); monitor.poll();
        verify(actions,times(1)).reconcilePr(execution.getId());
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.AWAITING_HITL);
    }
}
