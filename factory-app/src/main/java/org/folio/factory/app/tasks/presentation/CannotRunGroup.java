package org.folio.factory.app.tasks.presentation;

import java.util.List;

/** Tasks that cannot run here for the same reason: one line under Ready to run; reason is a whole sentence. */
public record CannotRunGroup(List<String> keys, String reason) {

    /** "2 tasks cannot run here: KEY-1, KEY-2. Project KEY has no repository in …". */
    public String line() {
        return keys.size() + (keys.size() == 1 ? " task cannot" : " tasks cannot") + " run here: "
                + String.join(", ", keys) + ". " + reason;
    }
}
