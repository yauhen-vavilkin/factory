package org.folio.factory.app.tasks.presentation;

import java.util.List;

/** The question the coding agent stopped with, read from the coding outcome of the latest attempt. */
public record AgentQuestion(String question, String evidence, List<String> options, String textBefore) {
}
