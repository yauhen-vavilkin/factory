package org.folio.factory.app.web;

import org.folio.factory.app.developer.DeveloperPreflight;
import org.folio.factory.app.tasks.persistence.TaskInstallationRepository;
import org.folio.factory.app.tasks.persistence.TaskInstallations;
import org.folio.factory.app.tasks.presentation.SourceCard;
import org.folio.factory.app.tasks.presentation.TaskRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class DeveloperPreflightControllerTest {
    @Mock
    private DeveloperPreflight preflight;

    @Mock
    private TaskRows rows;

    @Mock
    private TaskInstallationRepository installations;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DeveloperPreflightController(preflight, rows, installations))
                .build();
    }

    @Test
    void apiWithoutIssueReturnsAnOverviewWithoutClaimingTaskReadiness() throws Exception {
        var report = report(false, "", DeveloperPreflight.Status.NOT_RUN, "Enter an issue key.");
        when(preflight.check(null)).thenReturn(report);

        mvc.perform(get("/api/developer/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.issueKey").value(""))
                .andExpect(jsonPath("$.runtime.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.checks[0].status").value("NOT_RUN"));

        verify(preflight).check(null);
        verifyNoMoreInteractions(preflight);
    }

    @Test
    void apiReturnsBlockedReportAsDataWithTheRequestedIssue() throws Exception {
        var report = report(false, "MODSIDECAR-196", DeveloperPreflight.Status.FAIL,
                "Delivery destination is invalid.");
        when(preflight.check("MODSIDECAR-196")).thenReturn(report);

        mvc.perform(get("/api/developer/preflight").param("issueKey", "MODSIDECAR-196"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.issueKey").value("MODSIDECAR-196"))
                .andExpect(jsonPath("$.checks[0].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[0].message").value("Delivery destination is invalid."));

        verify(preflight).check("MODSIDECAR-196");
        verifyNoMoreInteractions(preflight);
    }

    @Test
    void configurationPageReceivesTheSameReportIncludingUntestedInference() throws Exception {
        var report = report(true, "MODSIDECAR-196", DeveloperPreflight.Status.PASS,
                "Destination contains the source base.");
        when(preflight.check("MODSIDECAR-196")).thenReturn(report);
        var installation = TaskInstallations.seeded();
        var source = new SourceCard("Jane Developer", "jira.example.org", "assignee = \"Jane Developer\"",
                "https://jira.example.org/issues/?jql=x", null, null, null);
        when(installations.findById(1)).thenReturn(Optional.of(installation));
        when(rows.sourceCard(installation)).thenReturn(source);
        when(rows.repositoryMap()).thenReturn("1 Jira project configured: MODSIDECAR → folio-module-sidecar");

        mvc.perform(get("/developer/configuration").param("issueKey", "MODSIDECAR-196"))
                .andExpect(status().isOk())
                .andExpect(view().name("developer-configuration"))
                .andExpect(model().attribute("report", report))
                .andExpect(model().attribute("source", source))
                .andExpect(model().attribute("repositoryMap",
                        "1 Jira project configured: MODSIDECAR → folio-module-sidecar"));

        verify(preflight).check("MODSIDECAR-196");
        verifyNoMoreInteractions(preflight);
    }

    private static DeveloperPreflight.Report report(boolean ready, String issueKey,
                                                     DeveloperPreflight.Status status, String message) {
        return new DeveloperPreflight.Report(ready, issueKey,
                Map.of("runtime", "claude-code", "model", "claude-sonnet-5-5"),
                List.of(Map.of("key", "sidecar", "source", "owner/folio-module-sidecar",
                        "baseBranch", "master", "buildImage", "maven:3.9-eclipse-temurin-21",
                        "verificationPlan", "java21-unit", "jiraProjects", List.of("MODSIDECAR"),
                        "delivery", "owner/folio-module-sidecar")),
                List.of(new DeveloperPreflight.Check("task", status, message),
                        new DeveloperPreflight.Check("inference", DeveloperPreflight.Status.NOT_RUN,
                                "No model call made.")),
                List.of());
    }
}
