package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.tasks.presentation.RunContext;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.core.repository.PipelineExecutionRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.NoSuchElementException;
import java.util.UUID;

/** The coding transcript of a Developer Flow run. */
@Controller
@RequiredArgsConstructor
public class TranscriptUiController {

    private final PipelineExecutionRepository executions;
    private final TaskQuery taskQuery;
    private final CodingTranscriptStore transcripts;
    private final ArtifactStore artifacts;

    @GetMapping("/executions/{id}/transcript")
    public String transcript(@PathVariable("id") UUID id, Model model) {
        var execution = executions.findById(id).orElseThrow(() -> new NoSuchElementException("No execution " + id));
        var taskRun = taskQuery.runContext(id);
        model.addAttribute("execution", execution);
        model.addAttribute("taskRun", taskRun.orElse(null));
        model.addAttribute("runCrumb", taskRun.map(RunContext::crumb)
                .orElse("run " + id.toString().substring(0, 4) + "…"));
        model.addAttribute("pageTitle", taskRun.map(run -> run.pageTitle() + " · transcript").orElse("Transcript"));
        var rounds = FeedbackUi.roundStarts(execution, artifacts.allForExecution(id));
        model.addAttribute("runs", DeveloperTranscriptView.sections(transcripts.read(id), rounds));
        return "transcript";
    }
}
