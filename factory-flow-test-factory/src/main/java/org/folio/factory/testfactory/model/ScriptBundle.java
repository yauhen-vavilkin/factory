package org.folio.factory.testfactory.model;

import java.util.List;

/**
 * Structured output of the Test Automation Agent: a set of generated test script
 * files carried inside a single bundle artifact.
 */
public record ScriptBundle(String framework, List<ScriptFile> files) {

    public ScriptBundle {
        files = files == null ? List.of() : files;
    }

    public record ScriptFile(String path, List<String> caseIds, String content) {

        public ScriptFile {
            caseIds = caseIds == null ? List.of() : caseIds;
        }
    }
}
