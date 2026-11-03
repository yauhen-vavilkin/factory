package org.folio.factory.devfactory.runtime;

/** How a yarn install treats the lockfile: {@code FROZEN} fails on any drift, {@code RESOLVE} may write it. */
public enum InstallMode {
    FROZEN,
    RESOLVE
}
