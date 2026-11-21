package org.folio.factory.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.servlet.resource.ResourceUrlProviderExposingInterceptor;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the console's CSS and JS under URLs that carry a content hash, so a browser never mixes
 * an old cached file with new HTML. Templates get the hashed URL from {@code @{...}} links; Spring
 * Boot's {@code spring.web.resources.chain} settings in application.yaml turn on that rewriting.
 * Only a URL with the current hash gets the one-year {@code immutable} header: its content never
 * changes. An unhashed URL such as {@code /css/app.css} gets no {@code Cache-Control}, and neither
 * does a 404 for an old hash, so a browser cannot keep a broken link for a year. A header set on
 * the resource handler would cover unhashed URLs too, and Boot's cache properties cannot set
 * {@code immutable}, so an interceptor sets it.
 */
@Configuration(proxyBeanMethods = false)
class StaticResourceConfiguration implements WebMvcConfigurer {

    static final CacheControl VERSIONED = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private static final Pattern HASH = Pattern.compile("-[0-9a-f]{32}(?=\\.(css|js)$)");

    private final String location;
    private final boolean cacheResources;

    /**
     * The defaults serve the files built into the jar and keep their hashes for the life of the JVM.
     * DemoConsole points {@code factory.console.static-location} at {@code src} and turns the cache off,
     * so a CSS edit shows on a normal reload without a restart.
     */
    StaticResourceConfiguration(
            @Value("${factory.console.static-location:classpath:/static/}") String location,
            @Value("${spring.web.resources.chain.cache:true}") boolean cacheResources) {
        this.location = location;
        this.cacheResources = cacheResources;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // One handler per folder: a shared one would also serve /css/poll.js.
        for (String folder : new String[] {"css", "js"}) {
            registry.addResourceHandler("/" + folder + "/**")
                    .addResourceLocations(location + folder + "/")
                    .resourceChain(cacheResources)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order(1): runs after Spring's own interceptor that puts the ResourceUrlProvider on the request.
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String uri = request.getRequestURI();
                Matcher hash = HASH.matcher(uri);
                if (hash.find()) {
                    // The URL a page would link to for the same file now; it differs when the hash is old.
                    var urls = (ResourceUrlProvider) request.getAttribute(
                            ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR);
                    if (uri.equals(urls.getForRequestUrl(request, hash.replaceFirst("")))) {
                        response.setHeader(HttpHeaders.CACHE_CONTROL, VERSIONED.getHeaderValue());
                    }
                }
                return true;
            }
        }).addPathPatterns("/css/**", "/js/**").order(1);
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // A page shows live state and one-time notices, so the Back button must load it again: a stored
                // copy would show an old state and repeat a notice such as "KEY is in Ready to run".
                response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
                return true;
            }
        }).excludePathPatterns("/css/**", "/js/**", "/api/**", "/actuator/**", "/error");
    }
}
