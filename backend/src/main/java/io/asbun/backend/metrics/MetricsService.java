package io.asbun.backend.metrics;

/**
 * Fire-and-forget application metrics. Implementations MUST be non-blocking and
 * failure-tolerant: a metrics emission must never fail, slow, or alter a user request
 * (swallow-and-log on error).
 *
 * <p>Two implementations exist, selected by the {@code monitoring.metrics.enabled} flag:
 * <ul>
 *   <li>{@link EmfMetricsService} — writes CloudWatch Embedded Metric Format (EMF) JSON log
 *       lines that CloudWatch auto-extracts into the {@code RecipeAiFinder/App} namespace. No
 *       extra IAM (logs already flow via awslogs). Active only when the flag is {@code true}.</li>
 *   <li>{@link NoOpMetricsService} — the default bean; every method returns immediately so the
 *       standard local/dev run and any deployment with the flag off are completely unaffected.</li>
 * </ul>
 *
 * <p>All values are emitted under the single namespace {@code RecipeAiFinder/App}, matching the
 * CloudWatch alarms and dashboard defined in {@code infrastructure/modules/monitoring}.
 */
public interface MetricsService {

    /** CloudWatch namespace all app metrics are published under. */
    String NAMESPACE = "RecipeAiFinder/App";

    /**
     * Emit a duration in milliseconds.
     *
     * @param metricName   e.g. {@code BedrockLatencyMs}
     * @param millis       the measured duration
     * @param dimensionName optional single dimension name (e.g. {@code Model}); null/blank = none
     * @param dimensionValue the dimension value (e.g. the model id)
     */
    void latencyMs(String metricName, long millis, String dimensionName, String dimensionValue);

    /**
     * Emit a counter increment (a count of {@code value}, usually 1).
     */
    void count(String metricName, double value, String dimensionName, String dimensionValue);

    /**
     * Emit a gauge (point-in-time value, e.g. an active-emitter count).
     */
    void gauge(String metricName, double value, String dimensionName, String dimensionValue);

    // ── Convenience overloads (no dimension) ───────────────────────────────────

    default void latencyMs(String metricName, long millis) {
        latencyMs(metricName, millis, null, null);
    }

    default void count(String metricName) {
        count(metricName, 1.0, null, null);
    }

    default void count(String metricName, double value) {
        count(metricName, value, null, null);
    }

    default void gauge(String metricName, double value) {
        gauge(metricName, value, null, null);
    }
}
