package org.folio.factory.flowa.model;

import java.util.List;

/**
 * Structured output of the Test Specification Agent. Carried in the frontmatter
 * of {@code test_plan.md}; the body is the human-readable rendering QA reviews.
 */
public record TestPlan(String issueKey, List<TestCase> cases) {

    public TestPlan {
        cases = cases == null ? List.of() : cases;
    }

    public record TestCase(
            String id,
            String title,
            String priority,
            String type,
            String targetEndpoint,
            List<String> preconditions,
            List<String> steps,
            String expected,
            String acceptanceCriteriaRef) {

        public TestCase {
            preconditions = preconditions == null ? List.of() : preconditions;
            steps = steps == null ? List.of() : steps;
        }
    }
}
