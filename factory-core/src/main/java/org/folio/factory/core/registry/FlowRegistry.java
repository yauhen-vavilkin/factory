package org.folio.factory.core.registry;

import jakarta.annotation.PostConstruct;
import org.folio.factory.core.domain.FlowRegistryEntry;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.folio.factory.core.repository.FlowRegistryEntryRepository;
import org.folio.factory.core.service.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The plugin layer: discovers YAML flow descriptors on the classpath at startup,
 * validates them, holds them in memory, and mirrors each into the database for
 * execution traceability. Adding a new flow means adding a module with a
 * descriptor under {@code flows/} — no control plane changes.
 */
@Component
public class FlowRegistry {

    private static final Logger log = LoggerFactory.getLogger(FlowRegistry.class);

    private final FlowDescriptorParser parser;
    private final FlowRegistryEntryRepository mirror;
    private final String locationPattern;
    private final Map<String, FlowDescriptor> flows = new LinkedHashMap<>();

    public FlowRegistry(FlowDescriptorParser parser,
                        FlowRegistryEntryRepository mirror,
                        @Value("${factory.flows.location-pattern:classpath*:flows/*.yaml}") String locationPattern) {
        this.parser = parser;
        this.mirror = mirror;
        this.locationPattern = locationPattern;
    }

    @PostConstruct
    public void loadFlows() {
        List<LoadedFlow> loaded = new ArrayList<>();
        for (Resource resource : scan()) {
            String yaml = read(resource);
            FlowDescriptor descriptor = parser.parse(yaml, resource.getFilename());
            if (flows.containsKey(descriptor.id())) {
                throw new FlowValidationException("Duplicate flow id '" + descriptor.id() + "' in " + resource.getFilename());
            }
            flows.put(descriptor.id(), descriptor);
            loaded.add(new LoadedFlow(descriptor, yaml));
        }
        validateSubFlowReferences();
        loaded.forEach(this::mirrorToDatabase);
        log.info("Flow registry loaded {} flow(s): {}", flows.size(), flows.keySet());
    }

    public Collection<FlowDescriptor> all() {
        return List.copyOf(flows.values());
    }

    public Optional<FlowDescriptor> find(String flowId) {
        return Optional.ofNullable(flows.get(flowId));
    }

    public FlowDescriptor require(String flowId) {
        return find(flowId).orElseThrow(
                () -> new FlowValidationException("No registered flow with id '" + flowId + "'"));
    }

    private void validateSubFlowReferences() {
        for (FlowDescriptor flow : flows.values()) {
            for (StepDescriptor step : flow.agentChain()) {
                if (step.type() == StepType.SUB_FLOW && !flows.containsKey(step.subFlow().flowId())) {
                    throw new FlowValidationException("Flow '" + flow.id() + "' step '" + step.stepId()
                            + "' references unregistered sub-flow '" + step.subFlow().flowId() + "'");
                }
            }
        }
    }

    private void mirrorToDatabase(LoadedFlow loadedFlow) {
        FlowDescriptor descriptor = loadedFlow.descriptor();
        String sha256 = ArtifactStore.sha256(loadedFlow.yaml());
        FlowRegistryEntry.Key key = new FlowRegistryEntry.Key(descriptor.id(), descriptor.version());
        mirror.findById(key).ifPresentOrElse(
                existing -> {
                    if (!existing.getYamlSha256().equals(sha256)) {
                        log.warn("Flow '{}' version {} changed content without a version bump; updating mirror",
                                descriptor.id(), descriptor.version());
                        existing.updateSnapshot(descriptor.name(), sha256, loadedFlow.yaml());
                        mirror.save(existing);
                    }
                },
                () -> mirror.save(new FlowRegistryEntry(descriptor.id(), descriptor.version(),
                        descriptor.name(), sha256, loadedFlow.yaml())));
    }

    private Resource[] scan() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan flow descriptors at " + locationPattern, e);
        }
    }

    private String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read flow descriptor " + resource.getFilename(), e);
        }
    }

    private record LoadedFlow(FlowDescriptor descriptor, String yaml) {
    }
}
