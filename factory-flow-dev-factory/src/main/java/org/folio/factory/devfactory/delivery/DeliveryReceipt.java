package org.folio.factory.devfactory.delivery;

/** Trusted delivery identity, persisted with the matching verification receipt. */
public record DeliveryReceipt(String executionId, String repository, String branch, String baseSha,
                              String treeSha, String patchSha256, String commitSha) { }
