package org.folio.factory.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * Serves the console's CSS and JS under URLs that carry a content hash, so a browser never mixes
 * an old cached file with new HTML. The hash changes with the file, so the browser may keep each URL
 * for a year. Templates get the hashed URL from {@code @{...}} links; Spring Boot's
 * {@code spring.web.resources.chain} settings in application.yaml turn on that rewriting.
 * This class exists because Boot's cache properties cannot set {@code immutable}.
 */
@Configuration(proxyBeanMethods = false)
class StaticResourceConfiguration implements WebMvcConfigurer {

    static final CacheControl VERSIONED = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // One handler per folder: a shared one would also serve /css/poll.js.
        for (String folder : new String[] {"css", "js"}) {
            registry.addResourceHandler("/" + folder + "/**")
                    .addResourceLocations("classpath:/static/" + folder + "/")
                    .setCacheControl(VERSIONED)
                    .resourceChain(true)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }
    }
}
