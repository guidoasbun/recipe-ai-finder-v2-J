package io.asbun.backend.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.reactor.ssl.TlsDetails;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;

import javax.net.ssl.SSLContext;

/**
 * Builds the {@link OpenSearchClient} used by the OpenSearch catalog search backend.
 *
 * <p>Two transports are supported, selected by {@code opensearch.auth}:
 * <ul>
 *   <li><b>{@code sigv4}</b> (default): AWS SigV4-signed requests via {@link AwsSdk2Transport},
 *       for Amazon OpenSearch (Serverless {@code aoss} or a managed domain {@code es}).</li>
 *   <li><b>{@code basic}</b>: HTTP basic auth over HTTPS via the Apache HttpClient 5 transport,
 *       for a self-hosted OpenSearch node (e.g. the Oracle Cloud free-tier box). Supports an
 *       optional TLS-verification toggle for a self-signed certificate.</li>
 * </ul>
 *
 * <p>This configuration is only active when {@code catalog.search.backend=opensearch}, so the
 * default (in-app) deployment neither requires an OpenSearch endpoint nor pulls the client
 * into the context.
 *
 * <p><b>Fail-fast:</b> when the OpenSearch backend is selected but {@code opensearch.endpoint}
 * is blank (or, for basic auth, the username/password are blank), bean creation throws so the
 * application refuses to start with a clear message, rather than silently serving empty or
 * stale results. Roll back with {@code catalog.search.backend=inapp}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.search.backend", havingValue = "opensearch")
public class OpenSearchConfig {

    private static final int DEFAULT_HTTPS_PORT = 443;

    private final OpenSearchProperties properties;

    @Value("${aws.region}")
    private String awsRegion;

    @Bean
    public OpenSearchClient openSearchClient() {
        String endpoint = properties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "catalog.search.backend=opensearch requires opensearch.endpoint to be set "
                            + "(env OPENSEARCH_ENDPOINT). Set the collection/domain/host, or roll "
                            + "back with catalog.search.backend=inapp.");
        }

        String auth = properties.getAuth() == null ? "sigv4" : properties.getAuth().trim();
        if ("basic".equalsIgnoreCase(auth)) {
            return new OpenSearchClient(buildBasicAuthTransport(endpoint));
        }
        return new OpenSearchClient(buildSigV4Transport(endpoint));
    }

    // ── AWS SigV4 transport (Amazon OpenSearch) ──────────────────────────────

    private OpenSearchTransport buildSigV4Transport(String endpoint) {
        String host = stripScheme(endpoint);
        String signingService = properties.getSigningService();
        Region region = Region.of(awsRegion);

        log.info("Configuring OpenSearch client (SigV4): host={}, region={}, signingService={}, index={}",
                host, awsRegion, signingService, properties.getIndex());

        // Bounded connection timeouts so a stuck/half-open TCP connection cannot wedge a PIT
        // page or a bulk request forever (a hung bulk would also hold its reindex semaphore
        // permit indefinitely and eventually deadlock the whole run). connectionMaxIdleTime
        // reaps stale pooled connections instead of reusing a dead one.
        return new AwsSdk2Transport(
                AwsCrtHttpClient.builder()
                        .connectionTimeout(java.time.Duration.ofSeconds(10))
                        .connectionMaxIdleTime(java.time.Duration.ofSeconds(30))
                        .build(),
                host,
                signingService,
                region,
                AwsSdk2TransportOptions.builder()
                        .setMapper(new JacksonJsonpMapper(docMapper()))
                        .setCredentials(DefaultCredentialsProvider.create())
                        .build());
    }

    // ── Basic-auth transport (self-hosted OpenSearch) ────────────────────────

    private OpenSearchTransport buildBasicAuthTransport(String endpoint) {
        String username = properties.getUsername();
        String password = properties.getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "opensearch.auth=basic requires opensearch.username and opensearch.password "
                            + "(env OPENSEARCH_USERNAME / OPENSEARCH_PASSWORD).");
        }

        HttpHost httpHost = toHttpHost(endpoint);
        boolean tlsVerify = properties.isTlsVerify();
        log.info("Configuring OpenSearch client (basic auth): host={}://{}:{}, tlsVerify={}, index={}",
                httpHost.getSchemeName(), httpHost.getHostName(), httpHost.getPort(), tlsVerify,
                properties.getIndex());

        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                new AuthScope(httpHost),
                new UsernamePasswordCredentials(username, password.toCharArray()));

        // Build a TLS strategy. For a self-signed cert (tlsVerify=false) trust all certs and
        // skip hostname verification; otherwise use the default (CA + hostname) verification.
        // The self-signed path is safe ONLY because the node's port is locked down by the OCI
        // security list to known source IPs (see the OCI Terraform module).
        final SSLContext sslContext = tlsVerify ? null : trustAllSslContext();

        return ApacheHttpClient5TransportBuilder.builder(httpHost)
                .setMapper(new JacksonJsonpMapper(docMapper()))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    if (!tlsVerify) {
                        var tlsStrategy = ClientTlsStrategyBuilder.create()
                                .setSslContext(sslContext)
                                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                // opensearch-java on HttpClient5 needs a non-null TlsDetails for
                                // the ALPN/next-protocol negotiation; supply a minimal one.
                                .setTlsDetailsFactory(sslEngine ->
                                        new TlsDetails(sslEngine.getSession(),
                                                sslEngine.getApplicationProtocol()))
                                .build();
                        var connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                                .setTlsStrategy(tlsStrategy)
                                .build();
                        httpClientBuilder.setConnectionManager(connectionManager);
                    }
                    return httpClientBuilder;
                })
                .build();
    }

    private static SSLContext trustAllSslContext() {
        try {
            // Trust every certificate — acceptable only behind an IP-restricted security list.
            return SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chain, authType) -> true)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build a trust-all SSLContext for opensearch.tls-verify=false", e);
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /**
     * Lenient mapper: OpenSearch documents carry fields the DTO does not declare (embedding,
     * ownerScope, searchText); ignore unknowns so hit deserialization into CatalogRecipeDto never
     * fails. JSR-310 for Instant support.
     */
    private static ObjectMapper docMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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

    /**
     * Parses the endpoint into an Apache {@link HttpHost} (scheme + host + port). Defaults to
     * HTTPS and, when no explicit port is given, the standard OpenSearch TLS port used by the
     * self-hosted node ({@value #DEFAULT_HTTPS_PORT} for a reverse-proxied node, or 9200 if the
     * endpoint names it explicitly).
     */
    static HttpHost toHttpHost(String endpoint) {
        String raw = endpoint.trim();
        String scheme = "https";
        if (raw.startsWith("https://")) {
            raw = raw.substring("https://".length());
        } else if (raw.startsWith("http://")) {
            scheme = "http";
            raw = raw.substring("http://".length());
        }
        if (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        String hostPart = raw;
        int port = -1;
        int colon = raw.lastIndexOf(':');
        if (colon > -1) {
            hostPart = raw.substring(0, colon);
            try {
                port = Integer.parseInt(raw.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid port in opensearch.endpoint: " + endpoint, e);
            }
        }
        if (port < 0) {
            port = "https".equals(scheme) ? DEFAULT_HTTPS_PORT : 80;
        }
        return new HttpHost(scheme, hostPart, port);
    }
}
