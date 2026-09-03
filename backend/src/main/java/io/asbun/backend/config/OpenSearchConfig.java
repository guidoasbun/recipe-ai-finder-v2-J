package io.asbun.backend.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;

/**
 * Builds the {@link OpenSearchClient} used by the OpenSearch catalog search backend, signing
 * requests with AWS SigV4 via {@link AwsSdk2Transport}.
 *
 * <p>This configuration is only active when {@code catalog.search.backend=opensearch}, so the
 * default (in-app) deployment neither requires an OpenSearch endpoint nor pulls the client
 * into the context.
 *
 * <p><b>Fail-fast:</b> when the OpenSearch backend is selected but {@code opensearch.endpoint}
 * is blank, bean creation throws so the application refuses to start with a clear message,
 * rather than silently serving empty or stale results. Roll back with
 * {@code catalog.search.backend=inapp}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.search.backend", havingValue = "opensearch")
public class OpenSearchConfig {

    private final OpenSearchProperties properties;

    @Value("${aws.region}")
    private String awsRegion;

    @Bean
    public OpenSearchClient openSearchClient() {
        String endpoint = properties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "catalog.search.backend=opensearch requires opensearch.endpoint to be set "
                            + "(env OPENSEARCH_ENDPOINT). Set the collection/domain host, or roll "
                            + "back with catalog.search.backend=inapp.");
        }

        String host = stripScheme(endpoint);
        String signingService = properties.getSigningService();
        Region region = Region.of(awsRegion);

        log.info("Configuring OpenSearch client: host={}, region={}, signingService={}, index={}",
                host, awsRegion, signingService, properties.getIndex());

        // Lenient mapper: OpenSearch documents carry fields the DTO does not declare
        // (embedding, ownerScope, searchText); ignore unknowns so hit deserialization into
        // CatalogRecipeDto never fails. JSR-310 for Instant support.
        ObjectMapper docMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // Bounded connection timeouts so a stuck/half-open TCP connection cannot wedge a PIT
        // page or a bulk request forever (a hung bulk would also hold its reindex semaphore
        // permit indefinitely and eventually deadlock the whole run). connectionMaxIdleTime
        // reaps stale pooled connections instead of reusing a dead one.
        OpenSearchTransport transport = new AwsSdk2Transport(
                AwsCrtHttpClient.builder()
                        .connectionTimeout(java.time.Duration.ofSeconds(10))
                        .connectionMaxIdleTime(java.time.Duration.ofSeconds(30))
                        .build(),
                host,
                signingService,
                region,
                AwsSdk2TransportOptions.builder()
                        .setMapper(new JacksonJsonpMapper(docMapper))
                        .setCredentials(DefaultCredentialsProvider.create())
                        .build());

        return new OpenSearchClient(transport);
    }

    /** AwsSdk2Transport expects a bare host, not a URL; drop any scheme and trailing slash. */
    private static String stripScheme(String endpoint) {
        String h = endpoint.trim();
        if (h.startsWith("https://")) {
            h = h.substring("https://".length());
        } else if (h.startsWith("http://")) {
            h = h.substring("http://".length());
        }
        if (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }
}
