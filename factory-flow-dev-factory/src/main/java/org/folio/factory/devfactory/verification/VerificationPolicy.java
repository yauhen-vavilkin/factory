package org.folio.factory.devfactory.verification;

import org.folio.factory.devfactory.DevFactoryProperties;
import org.folio.factory.devfactory.candidate.Candidate;
import org.folio.factory.devfactory.runtime.DevRuntimeProperties;

/** Current trusted configuration, separate from the receipt's immutable candidate identity. */
public final class VerificationPolicy {
    private final DevFactoryProperties repositories;
    private final DevRuntimeProperties runtime;

    public VerificationPolicy(DevFactoryProperties repositories, DevRuntimeProperties runtime) {
        this.repositories = repositories;
        this.runtime = runtime;
    }

    public boolean matches(Candidate candidate, VerificationReceipt receipt) {
        var repository = repositories.repositories().get(candidate.repository());
        return repository != null
                && repository.verificationPlan().equals(receipt.planId())
                && repository.buildImage().equals(receipt.image())
                && runtime.command(repository.verificationPlan()).equals(receipt.argv())
                && runtime.requiredReports(repository.verificationPlan())
                        .equals(receipt.requiredReports());
    }
}
