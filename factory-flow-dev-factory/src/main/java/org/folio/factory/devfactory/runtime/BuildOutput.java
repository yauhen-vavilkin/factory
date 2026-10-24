package org.folio.factory.devfactory.runtime;

import java.util.function.Consumer;

/** Bounded, sanitized progress of the starting build; one implementation per toolchain. */
public interface BuildOutput extends Consumer<String> {
    void heartbeat();

    void close();
}
