package com.pxe.stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The console runs on a different port from the API, so every call the browser makes is
 * cross-origin: the SSE stream, the counters poll, and the one POST that can spend a token.
 *
 * <p>Without this an EventSource fails on open and reports nothing useful: the page shows a
 * finished stream with an empty timeline, which looks like a backend that returned no hops rather
 * than a browser that was never allowed to ask.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfig(@Value("${pxe.cors-origins}") String origins) {
        this.origins = origins.split(",");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}
