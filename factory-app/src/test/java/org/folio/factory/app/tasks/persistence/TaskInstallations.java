package org.folio.factory.app.tasks.persistence;

/** The installation row that migration V12 seeds, for tests that clear the table and recreate it. */
public final class TaskInstallations {

    private TaskInstallations() {
    }

    public static TaskInstallation seeded() {
        return new TaskInstallation();
    }
}
