package org.folio.factory.devfactory.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/** Trusted build environment of a workload: toolchain, dependency-cache volume and, for yarn, the FOLIO npm registry. */
public record BuildEnvironment(Toolchain toolchain, String cacheVolume, String npmRegistry) {
    static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    /** The registry is written into a shell command, so it is limited to plain URL characters. */
    static final Pattern REGISTRY = Pattern.compile("https?://[A-Za-z0-9._~:/-]+");

    public BuildEnvironment {
        Objects.requireNonNull(toolchain, "toolchain");
        if (cacheVolume == null || !VOLUME.matcher(cacheVolume).matches())
            throw new IllegalArgumentException("Invalid dependency cache volume name");
        if (toolchain == Toolchain.YARN && (npmRegistry == null || !REGISTRY.matcher(npmRegistry).matches()))
            throw new IllegalArgumentException("Invalid FOLIO npm registry");
    }

    public static BuildEnvironment maven(String cacheVolume) {
        return new BuildEnvironment(Toolchain.MAVEN, cacheVolume, null);
    }
}
