package org.folio.factory.app.web;

import java.util.List;

/**
 * The Configuration tab of the Developer Flow, top to bottom: the models, where runs go, the limits of every
 * run, where tasks come from, whether Factory can start runs, and the check of one task. Each value names the
 * file or variable that changes it, never a secret.
 */
public record DeveloperConfigurationView(Models models, Routes routes, List<Fact> limits, Source source,
                                         Start start, TaskCheck task) {

    /**
     * Ready to run, Endpoint not reachable and Cannot run; note says where the models come from, or that Pi has
     * its own model. The run forms offer the models of the first two groups.
     */
    public record Models(boolean listed, List<ReadyModel> ready, List<UnreachableEndpoint> unreachable,
                         List<BlockedModel> blocked, String note) {

        /** Some model can run, even if its endpoint is not reachable from Factory. */
        public boolean runnable() {
            return !ready.isEmpty() || !unreachable.isEmpty();
        }

        public int unreachableCount() {
            return unreachable.stream().mapToInt(endpoint -> endpoint.models().size()).sum();
        }
    }

    /** A model the run forms offer; warnings are fix sentences that do not stop a run. */
    public record ReadyModel(String id, String summary, List<String> warnings) {
    }

    /** An endpoint Factory cannot connect to: the warning once, then the models that use it. */
    public record UnreachableEndpoint(String origin, String warning, List<ReadyModel> models) {
    }

    /** A model the run forms leave out, with the fix sentence of each failing check. */
    public record BlockedModel(String name, List<String> fixes) {
    }

    /** One row per repository; token says whether Factory can open pull requests at all. */
    public record Routes(List<Route> rows, String token) {
    }

    public record Route(String projects, String source, String pullRequests, String checks) {
    }

    /** A name, its value or fix sentence, and the variable that changes it (null when the text names it). */
    public record Fact(String name, String text, String variable) {
    }

    /** Where My tasks come from; query and searchUrl are null until Jira and the assignee are set. */
    public record Source(List<Fact> facts, String query, String searchUrl) {
    }

    /** Whether Factory can start runs, and the fix of each problem when it cannot. */
    public record Start(String title, String detail, List<String> fixes) {
    }

    /** The issue-key check; verdict is null until a key is entered. */
    public record TaskCheck(String issueKey, String verdict, List<CheckRow> rows) {
    }

    /** One check of the task: the chip word and tone, and the message. */
    public record CheckRow(String label, String chipClass, String text) {
    }
}
