package io.asbun.backend.config;

import io.asbun.backend.search.CatalogSearchService;
import io.asbun.backend.search.InAppCatalogSearchService;
import io.asbun.backend.search.OpenSearchCatalogSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CatalogSearchConfig backend selection by the catalog.search.backend property.
 *
 * Feature: opensearch-catalog-backend
 * Validates: Task 8.4 (Requirements 1.4, 1.5)
 */
class CatalogSearchConfigTest {

    private final CatalogSearchConfig config = new CatalogSearchConfig();

    @SuppressWarnings("unchecked")
    private ObjectProvider<InAppCatalogSearchService> inAppProvider(InAppCatalogSearchService bean) {
        ObjectProvider<InAppCatalogSearchService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<OpenSearchCatalogSearchService> openSearchProvider(OpenSearchCatalogSearchService bean) {
        ObjectProvider<OpenSearchCatalogSearchService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @Test
    void defaultsToInApp() {
        InAppCatalogSearchService inApp = mock(InAppCatalogSearchService.class);
        CatalogSearchService selected = config.catalogSearchService(
                "inapp", inAppProvider(inApp), openSearchProvider(null));
        assertThat(selected).isSameAs(inApp);
    }

    @Test
    void selectsOpenSearchWhenConfiguredAndAvailable() {
        InAppCatalogSearchService inApp = mock(InAppCatalogSearchService.class);
        OpenSearchCatalogSearchService os = mock(OpenSearchCatalogSearchService.class);
        CatalogSearchService selected = config.catalogSearchService(
                "opensearch", inAppProvider(inApp), openSearchProvider(os));
        assertThat(selected).isSameAs(os);
    }

    @Test
    void opensearchSelectedButUnavailable_failsFast() {
        InAppCatalogSearchService inApp = mock(InAppCatalogSearchService.class);
        assertThatThrownBy(() -> config.catalogSearchService(
                "opensearch", inAppProvider(inApp), openSearchProvider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opensearch");
    }
}
