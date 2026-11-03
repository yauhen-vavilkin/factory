package org.folio.factory.app.web;

import lombok.RequiredArgsConstructor;
import org.folio.factory.app.developer.CodingTranscriptStore;
import org.folio.factory.app.tasks.presentation.TaskQuery;
import org.folio.factory.core.repository.PipelineExecutionRepository;
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

    @GetMapping("/executions/{id}/transcript")
    public String transcript(@PathVariable("id") UUID id, Model model) {
        var execution = executions.findById(id).orElseThrow(() -> new NoSuchElementException("No execution " + id));
        model.addAttribute("execution", execution);
        model.addAttribute("taskRun", taskQuery.runContext(id).orElse(null));
        model.addAttribute("runs", DeveloperTranscriptView.runs(transcripts.read(id)));
        return "transcript";
    }
}
