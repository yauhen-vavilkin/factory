package org.folio.factory.devfactory;

import org.folio.factory.devfactory.runtime.DevRuntimeProperties;

/** The operator's Developer Flow configuration: the repository catalog and the runtime settings. */
public record DevFlowSettings(DevFactoryProperties repositories, DevRuntimeProperties runtime) {
}
