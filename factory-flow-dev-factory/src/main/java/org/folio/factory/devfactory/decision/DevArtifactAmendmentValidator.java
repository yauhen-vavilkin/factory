package org.folio.factory.devfactory.decision;

import lombok.RequiredArgsConstructor;
import org.folio.factory.agents.artifact.ArtifactFormatException;
import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.core.hitl.ArtifactAmendmentValidator;
import org.folio.factory.devfactory.repository.RepositoryPolicy;
import org.folio.factory.devfactory.worker.IntakeWorker;
import org.folio.factory.devfactory.worker.IntakeResolveWorker;
import org.folio.factory.devfactory.worker.DevelopWorker;

import java.util.Set;

/**
 * Reviewers may amend only the decision answer, and only to a configured
 * repository. Trusted Developer artifacts (issue snapshot, decision request, task
 * brief) cannot be edited through HITL. Other flows' artifacts are ignored.
 */
@RequiredArgsConstructor
public class DevArtifactAmendmentValidator implements ArtifactAmendmentValidator {

    private static final Set<String> TRUSTED = Set.of(
            IntakeWorker.INTAKE, DecisionArtifacts.REQUEST, IntakeResolveWorker.TASK_BRIEF,
            IntakeResolveWorker.CODING_REQUEST, DevelopWorker.OUTCOME, CodingDecisionArtifacts.REQUEST);

    private final FrontmatterCodec codec;
    private final RepositoryPolicy policy;

    @Override
    public void validate(String artifactName, String content) {
        if (TRUSTED.contains(artifactName)) {
            throw new IllegalArgumentException(
                    "'" + artifactName + "' is trusted Developer Flow data and cannot be amended");
        }
        if (CodingDecisionArtifacts.ANSWER.equals(artifactName)) {
            validateCodingAnswer(content);
        } else if (DecisionArtifacts.ANSWER.equals(artifactName)) {
            validateRepositoryAnswer(content);
        }
    }

    private void validateCodingAnswer(String content) {
        try {
            CodingDecisionArtifacts.parseAnswer(codec, content);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Amended coding decision answer is not valid: "
                    + e.getMessage(), e);
        }
    }

    private void validateRepositoryAnswer(String content) {
        DecisionArtifacts.Answer answer;
        try {
            answer = DecisionArtifacts.parseAnswer(codec, content);
        } catch (ArtifactFormatException e) {
            throw new IllegalArgumentException("Amended decision answer is not valid: " + e.getMessage(), e);
        }
        if (policy.find(answer.choice()).isEmpty()) {
            throw new IllegalArgumentException("Decision answer choice '" + answer.choice()
                    + "' is not a configured repository");
        }
    }
}
