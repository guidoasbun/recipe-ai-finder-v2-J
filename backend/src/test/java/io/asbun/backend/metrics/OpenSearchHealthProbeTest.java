package io.asbun.backend.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.HealthStatus;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.opensearch.client.opensearch.cluster.OpenSearchClusterClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchHealthProbeTest {

    private OpenSearchHealthProbe probeFor(OpenSearchClient client, MetricsService metrics,
                                           String backend, String auth) {
        return new OpenSearchHealthProbe(client, metrics, backend, auth);
    }

    // ── No-op unless the live backend is the basic-auth OCI node ───────────────
    @Test
    void isNoOpWhenBackendIsNotBasicAuthNode() {
        OpenSearchClient client = mock(OpenSearchClient.class);
        MetricsService metrics = mock(MetricsService.class);

        // in-app backend
        probeFor(client, metrics, "inapp", "sigv4").probe();
        // opensearch but sigv4 (AWS) — native metrics cover it, so the probe stays off
        probeFor(client, metrics, "opensearch", "sigv4").probe();

        verify(metrics, never()).gauge(eq("OpenSearchNodeUp"), org.mockito.ArgumentMatchers.anyDouble());
    }

    // ── Healthy node: green maps to status score 2 and NodeUp=1 ────────────────
    @Test
    void emitsUpAndStatusWhenHealthy() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        OpenSearchClusterClient cluster = mock(OpenSearchClusterClient.class);
        HealthResponse health = mock(HealthResponse.class);
        MetricsService metrics = mock(MetricsService.class);

        when(client.cluster()).thenReturn(cluster);
        when(cluster.health()).thenReturn(health);
        when(health.status()).thenReturn(HealthStatus.Green);

        probeFor(client, metrics, "opensearch", "basic").probe();

        verify(metrics).gauge("OpenSearchNodeUp", 1.0);
        verify(metrics).gauge("OpenSearchClusterStatus", 2.0);
        verify(metrics).latencyMs(eq("OpenSearchHealthProbeLatencyMs"), org.mockito.ArgumentMatchers.anyLong());
    }

    // ── Unreachable node: emit NodeUp=0 and DO NOT throw ───────────────────────
    @Test
    void emitsDownAndDoesNotThrowWhenClientFails() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        OpenSearchClusterClient cluster = mock(OpenSearchClusterClient.class);
        MetricsService metrics = mock(MetricsService.class);

        when(client.cluster()).thenReturn(cluster);
        when(cluster.health()).thenThrow(new java.io.IOException("connection refused"));

        OpenSearchHealthProbe probe = probeFor(client, metrics, "opensearch", "basic");

        assertThatCode(probe::probe).doesNotThrowAnyException();
        verify(metrics).gauge("OpenSearchNodeUp", 0.0);
        verify(metrics, never()).gauge("OpenSearchNodeUp", 1.0);
    }
}
