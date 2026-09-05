package org.folio.factory.devfactory.inbox;

/** Raised when an inbox task file is unreadable, malformed, or invalid. */
public class InboxTaskFileException extends RuntimeException {

    public InboxTaskFileException(String message) {
        super(message);
    }

    public InboxTaskFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
