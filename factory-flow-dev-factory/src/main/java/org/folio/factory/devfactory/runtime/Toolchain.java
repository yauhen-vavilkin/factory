package org.folio.factory.devfactory.runtime;

import java.util.Optional;
import java.util.function.Consumer;

/** Build ecosystem of a verification plan; decides how its test evidence and build output are read. */
public enum Toolchain {
    MAVEN,
    YARN;

    public BuildOutput output(Consumer<String> observer) {
        return switch (this) {
            case MAVEN -> new MavenBaselineOutput(observer);
            case YARN -> new YarnBaselineOutput(observer);
        };
    }

    public Optional<String> failureSummary(String output) {
        return switch (this) {
            case MAVEN -> MavenBaselineOutput.failureSummary(output);
            case YARN -> YarnBaselineOutput.failureSummary(output);
        };
    }

    public boolean isTransientDownloadFailure(String output) {
        return switch (this) {
            case MAVEN -> MavenBaselineOutput.isTransientDownloadFailure(output);
            case YARN -> YarnBaselineOutput.isTransientDownloadFailure(output);
        };
    }
}
