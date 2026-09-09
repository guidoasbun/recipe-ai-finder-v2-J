package io.asbun.backend.metrics;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled health probe for the self-hosted OpenSearch node on Oracle Cloud. This is the ONLY
 * way CloudWatch can observe that node: it runs on OCI (not AWS), so native CloudWatch cannot
 * reach it. The probe calls the node's cluster-health API and pushes the result as custom
 * metrics via {@link MetricsService} (design §5.3), which the {@code opensearch-node-down} and
 * {@code opensearch-cluster-red} alarms read.
 *
 * <p>Guarding: the bean only exists when {@code monitoring.metrics.enabled=true} AND the live
 * backend is actually the basic-auth OCI node ({@code catalog.search.backend=opensearch} +
 * {@code opensearch.auth=basic}). For the in-app fallback or a SigV4/AWS backend it is not
 * created, so it is a true no-op there. It also requires the {@link OpenSearchClient} bean, which
 * only exists when the OpenSearch backend is selected.
 *
 * <p>Failure tolerance: the probe never throws. Any error/timeout is treated as the node being
 * down — it emits {@code OpenSearchNodeUp=0} and returns. It must not affect request handling.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.metrics.enabled", havingValue = "true")
@ConditionalOnBean(OpenSearchClient.class)
public class OpenSearchHealthProbe {

    private static final String M_NODE_UP = "OpenSearchNodeUp";
    private static final String M_CLUSTER_STATUS = "OpenSearchClusterStatus";
    private static final String M_PROBE_LATENCY = "OpenSearchHealthProbeLatencyMs";

    private final OpenSearchClient client;
    private final MetricsService metrics;
    private final boolean activeBackend;

    public OpenSearchHealthProbe(
            OpenSearchClient client,
            MetricsService metrics,
            @Value("${catalog.search.backend:inapp}") String searchBackend,
            @Value("${opensearch.auth:sigv4}") String auth) {
        this.client = client;
        this.metrics = metrics;
        // Only probe the self-hosted node. For sigv4/AWS the node-health metrics are meaningless
        // (AWS-native metrics cover Serverless), and for in-app there's no node at all.
        this.activeBackend = "opensearch".equalsIgnoreCase(searchBackend)
                && "basic".equalsIgnoreCase(auth);
        if (activeBackend) {
            log.info("OpenSearch health probe active (self-hosted basic-auth node).");
        } else {
            log.info("OpenSearch health probe inactive (backend={}, auth={}); no node metrics emitted.",
                    searchBackend, auth);
        }
    }

    /**
     * Probe the node once a minute. Emits reachability (1/0), cluster status
     * (green=2/yellow=1/red=0), and probe latency. Swallows every failure as "node down".
     */
    @Scheduled(fixedRate = 60_000L)
    public void probe() {
        if (!activeBackend) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            HealthResponse health = client.cluster().health();
            long elapsed = System.currentTimeMillis() - start;

            metrics.gauge(M_NODE_UP, 1.0);
            metrics.gauge(M_CLUSTER_STATUS, statusToScore(health));
            metrics.latencyMs(M_PROBE_LATENCY, elapsed);
        } catch (Exception e) {
            // Unreachable / timeout / auth error → the node is not serving. Report down.
            metrics.gauge(M_NODE_UP, 0.0);
            metrics.latencyMs(M_PROBE_LATENCY, System.currentTimeMillis() - start);
            log.debug("OpenSearch health probe failed (reporting node down): {}", e.getMessage());
        }
    }

    // green=2, yellow=1, red/unknown=0 — matches the OpenSearchClusterStatus alarm thresholds.
    private static double statusToScore(HealthResponse health) {
        if (health == null || health.status() == null) {
            return 0.0;
        }
        return switch (health.status()) {
            case Green -> 2.0;
            case Yellow -> 1.0;
            default -> 0.0; // Red
        };
    }
}
