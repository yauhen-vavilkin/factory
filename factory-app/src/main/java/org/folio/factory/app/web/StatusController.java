package org.folio.factory.app.web;

import org.folio.factory.connectors.ConnectorHealth;
import org.folio.factory.core.engine.EngineProperties;
import org.folio.factory.core.registry.FlowRegistry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class StatusController {

    private final List<ConnectorHealth> connectors;
    private final FlowRegistry flowRegistry;
    private final EngineProperties engineProperties;

    public StatusController(List<ConnectorHealth> connectors, FlowRegistry flowRegistry,
                            EngineProperties engineProperties) {
        this.connectors = connectors;
        this.flowRegistry = flowRegistry;
        this.engineProperties = engineProperties;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> connectorStatus = new LinkedHashMap<>();
        for (ConnectorHealth connector : connectors) {
            connectorStatus.put(connector.connectorName(), connector.isConfigured() ? "configured" : "not configured");
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("engineEnabled", engineProperties.enabled());
        status.put("connectors", connectorStatus);
        status.put("flows", flowRegistry.all().stream()
                .map(f -> Map.of("id", f.id(), "name", f.name(), "version", f.version()))
                .toList());
        return status;
    }
}
