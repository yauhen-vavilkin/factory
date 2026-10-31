package org.folio.factory.devfactory.feedback;

import org.folio.factory.connectors.github.GitHubProperties;

/** What a PR feedback round reads and updates the pull request with. */
public record FeedbackServices(PrFeedbackGateway gateway, FeedbackGit git, GitHubProperties github) {
}
