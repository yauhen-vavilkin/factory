package org.folio.factory.app.tasks.presentation;

import java.util.List;

/** Tasks that cannot run here for the same reason, shown as one line under "N cannot run here". */
public record CannotRunGroup(List<String> keys, String reason) {

    public String line() {
        return String.join(", ", keys) + ": " + reason;
    }
}
