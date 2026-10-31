package org.folio.factory.devfactory.tasks;

/** The state and reason that end a Developer Flow attempt early; both are shown to the operator. */
public record Stop(String state, String reason) {
}
