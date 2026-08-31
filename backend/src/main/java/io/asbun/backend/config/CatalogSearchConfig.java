package io.asbun.backend.config;

import io.asbun.backend.search.CatalogSearchService;
import io.asbun.backend.search.InAppCatalogSearchService;
import io.asbun.backend.search.OpenSearchCatalogSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the active {@link CatalogSearchService} implementation from
 * {@code catalog.search.backend} (default {@code inapp}). Setting it to {@code opensearch}
 * selects {@link OpenSearchCatalogSearchService}; the controller, DTOs, and frontend are
 * unaffected — that is the point of the seam.
 *
 * <p>The OpenSearch bean is {@code @ConditionalOnProperty(backend=opensearch)}, so it only
 * exists when selected. It is injected via {@link ObjectProvider} so this factory resolves in
 * either mode without a missing-bean failure.
 */
@Slf4j
@Configuration
public class CatalogSearchConfig {

    @Bean
    @Primary
    public CatalogSearchService catalogSearchService(
            @Value("${catalog.search.backend:inapp}") String backend,
            ObjectProvider<InAppCatalogSearchService> inApp,
            ObjectProvider<OpenSearchCatalogSearchService> openSearch) {
        if ("opensearch".equalsIgnoreCase(backend)) {
            OpenSearchCatalogSearchService os = openSearch.getIfAvailable();
            if (os != null) {
                log.info("Using OpenSearch catalog search backend.");
                return os;
            }
            // The conditional bean should exist when backend=opensearch; if it does not, the
            // client bean likely failed to configure. Fail loudly rather than silently serving
            // in-app results for a deliberate opensearch selection.
            throw new IllegalStateException(
                    "catalog.search.backend=opensearch but OpenSearchCatalogSearchService is not "
                            + "available. Check OpenSearch client configuration (opensearch.endpoint), "
                            + "or roll back with catalog.search.backend=inapp.");
        }
        return inApp.getObject();
    }
}
