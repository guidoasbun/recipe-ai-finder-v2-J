package io.asbun.backend.metrics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default metrics bean: does nothing. Active whenever {@code monitoring.metrics.enabled} is
 * absent or {@code false} (the {@code matchIfMissing = true} case), so the standard local/dev
 * run and any deployment with monitoring off emit no metrics and pay no cost. The
 * {@link EmfMetricsService} replaces it when the flag is {@code true}.
 */
@Component
@ConditionalOnProperty(name = "monitoring.metrics.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpMetricsService implements MetricsService {

    @Override
    public void latencyMs(String metricName, long millis, String dimensionName, String dimensionValue) {
        // no-op
    }

    @Override
    public void count(String metricName, double value, String dimensionName, String dimensionValue) {
        // no-op
    }

    @Override
    public void gauge(String metricName, double value, String dimensionName, String dimensionValue) {
        // no-op
    }
}
