package io.asbun.backend.config;

import io.asbun.backend.search.CatalogSearchService;
import io.asbun.backend.search.InAppCatalogSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the active {@link CatalogSearchService} implementation from
 * {@code catalog.search.backend} (default {@code inapp}). When an OpenSearch implementation
 * is added later, wire it here and set the property to {@code opensearch}; nothing else
 * (controller, DTOs, frontend) changes.
 */
@Slf4j
@Configuration
public class CatalogSearchConfig {

    @Bean
    @Primary
    public CatalogSearchService catalogSearchService(
            @Value("${catalog.search.backend:inapp}") String backend,
            ObjectProvider<InAppCatalogSearchService> inApp) {
        // Only the in-app backend exists in Phase 1. The switch is here so a future
        // OpenSearch bean plugs in without touching callers.
        if ("opensearch".equalsIgnoreCase(backend)) {
            log.warn("catalog.search.backend=opensearch requested but no OpenSearch backend is "
                    + "implemented yet; falling back to in-app search.");
        }
        return inApp.getObject();
    }
}
