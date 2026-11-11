package org.folio.factory.app.tasks.presentation;

/** Whose move it is, said at the top of a status card so the developer knows whether the page needs them. */
public enum Turn {
    YOURS("Your turn"), WORKING("Factory is working"), QUEUED("Waiting in the queue"),
    NONE("Nothing needed from you");

    private final String label;

    Turn(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean yours() {
        return this == YOURS;
    }
}
