package org.folio.factory.agents.artifact;

public class ArtifactFormatException extends RuntimeException {

    public ArtifactFormatException(String message) {
        super(message);
    }

    public ArtifactFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
