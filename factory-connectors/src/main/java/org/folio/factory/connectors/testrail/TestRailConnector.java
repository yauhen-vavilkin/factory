package org.folio.factory.connectors.testrail;

import java.util.List;
import java.util.Map;

public interface TestRailConnector {

    /**
     * Creates a test case in the given section.
     *
     * @return the created case id
     */
    long addCase(long sectionId, String title, String steps, String expected);

    /**
     * Creates a test run limited to the given case ids.
     *
     * @return the created run id
     */
    long addRun(String name, List<Long> caseIds);

    /**
     * Records results for cases in a run. Map: caseId → passed.
     */
    void addResults(long runId, Map<Long, Boolean> results, String comment);
}
