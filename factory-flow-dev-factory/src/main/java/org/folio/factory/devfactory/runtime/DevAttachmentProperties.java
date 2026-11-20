package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Limits for the Jira images handed to the coding agent. An unset {@code maxImages} means 10 and a
 * {@code maxImages} of 0 means no limit; a blank {@code maxBytes} means the default.
 */
@ConfigurationProperties(prefix = "factory.dev-factory.attachments")
public record DevAttachmentProperties(Integer maxImages, Long maxBytes) {
    public static final int DEFAULT_MAX_IMAGES = 10;
    public static final long DEFAULT_MAX_BYTES = 3L * 1024 * 1024;

    public DevAttachmentProperties() {
        this(null, null);
    }

    @ConstructorBinding
    public DevAttachmentProperties {
        maxImages = maxImages == null ? DEFAULT_MAX_IMAGES : maxImages;
        if (maxImages < 0) throw new IllegalArgumentException("attachments max-images must not be negative");
        maxBytes = maxBytes == null || maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }
}
