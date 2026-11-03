package org.folio.factory.devfactory;

import org.folio.factory.devfactory.candidate.CandidateFreezer;
import org.folio.factory.devfactory.runtime.CodingRuntime;
import org.folio.factory.devfactory.runtime.DockerWorkloads;

/** What a coding attempt runs on: the coding CLI, its Docker workloads and the candidate freezer. */
public record CodingServices(CodingRuntime coding, DockerWorkloads docker, CandidateFreezer freezer) {
}
