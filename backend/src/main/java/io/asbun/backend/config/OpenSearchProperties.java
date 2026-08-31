package io.asbun.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Connection and k-NN tuning configuration for the OpenSearch catalog backend. Only
 * meaningful when {@code catalog.search.backend=opensearch}; the client bean
 * ({@link OpenSearchConfig}) is created conditionally on that property.
 */
@Data
@Component
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

    /**
     * HTTPS host of the serverless collection or managed domain. Blank by default so the
     * default (in-app) deployment requires nothing. Required when the OpenSearch backend is
     * selected — {@link OpenSearchConfig} fails fast if it is blank.
     */
    private String endpoint = "";

    /** Index (managed) / collection index name to search and reindex into. */
    private String index = "catalog-recipes";

    /**
     * AWS SigV4 signing service name: {@code aoss} for OpenSearch Serverless, {@code es} for a
     * managed domain. Defaults to serverless (the chosen flavor).
     */
    private String signingService = "aoss";

    private Knn knn = new Knn();

    @Data
    public static class Knn {
        /** k-NN {@code ef_search} at query time; higher = better recall, slower. */
        private int efSearch = 100;

        /** Vector quantization: {@code none} | {@code fp16} | {@code byte}. See design.md §3. */
        private String quantization = "none";
    }
}
