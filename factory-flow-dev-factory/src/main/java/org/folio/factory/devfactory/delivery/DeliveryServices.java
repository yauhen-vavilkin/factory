package org.folio.factory.devfactory.delivery;

import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.connectors.github.GitHubProperties;

/** What a delivery publishes with: the branch push, the pull request API and the GitHub credentials. */
public record DeliveryServices(CandidateDelivery delivery, GitHubConnector github, GitHubProperties githubProperties) {
}
