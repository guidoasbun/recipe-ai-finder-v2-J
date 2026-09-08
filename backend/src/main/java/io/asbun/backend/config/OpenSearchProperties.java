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
     * managed domain. Defaults to serverless (the chosen flavor). Only consulted when
     * {@code auth=sigv4}.
     */
    private String signingService = "aoss";

    /**
     * Transport auth mode: {@code sigv4} (AWS SigV4 signing, for Amazon OpenSearch) or
     * {@code basic} (HTTP basic auth over HTTPS, for a self-hosted OpenSearch node such as the
     * Oracle Cloud free-tier box). Defaults to {@code sigv4} so existing AWS deployments are
     * unchanged.
     */
    private String auth = "sigv4";

    /** Basic-auth username. Required when {@code auth=basic}. */
    private String username = "";

    /** Basic-auth password. Required when {@code auth=basic}. */
    private String password = "";

    /**
     * When {@code auth=basic}, verify the server's TLS certificate. Leave {@code true} for a
     * real/CA-signed cert. Set {@code false} ONLY for a self-signed cert on a locked-down host
     * (dev/free-tier); this disables hostname + chain verification, so use with a security list
     * that restricts who can reach port 9200.
     */
    private boolean tlsVerify = true;

    private Knn knn = new Knn();

    @Data
    public static class Knn {
        /** k-NN {@code ef_search} at query time; higher = better recall, slower. */
        private int efSearch = 100;

        /**
         * Vector quantization: {@code none} | {@code fp16} | {@code byte}. See design.md §3.
         * {@code byte} (Faiss SQ int8) uses ~1/4 the vector memory of full float and ~1/2 of
         * fp16 — required to fit the 2.2M-doc index in the Oracle free-tier 12 GB box.
         */
        private String quantization = "none";
    }
}
