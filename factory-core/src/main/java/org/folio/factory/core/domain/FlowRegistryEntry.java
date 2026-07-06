package org.folio.factory.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;

/**
 * Database mirror of a YAML flow descriptor, kept so every execution can be traced
 * back to the exact descriptor content it ran under. The YAML on the classpath is
 * the source of truth; this row is a snapshot.
 */
@Entity
@Table(name = "flow_registry")
public class FlowRegistryEntry {

    @Embeddable
    public record Key(
            @Column(name = "flow_id") String flowId,
            @Column(name = "version") String version) implements Serializable {
    }

    @EmbeddedId
    private Key key;

    @Column(nullable = false)
    private String name;

    @Column(name = "yaml_sha256", nullable = false, length = 64)
    private String yamlSha256;

    @Column(name = "raw_yaml", nullable = false, columnDefinition = "text")
    private String rawYaml;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected FlowRegistryEntry() {
    }

    public FlowRegistryEntry(String flowId, String version, String name, String yamlSha256, String rawYaml) {
        this.key = new Key(flowId, version);
        this.name = name;
        this.yamlSha256 = yamlSha256;
        this.rawYaml = rawYaml;
        this.registeredAt = Instant.now();
    }

    public Key getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getYamlSha256() {
        return yamlSha256;
    }

    public void updateSnapshot(String name, String yamlSha256, String rawYaml) {
        this.name = name;
        this.yamlSha256 = yamlSha256;
        this.rawYaml = rawYaml;
        this.registeredAt = Instant.now();
    }

    public String getRawYaml() {
        return rawYaml;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
