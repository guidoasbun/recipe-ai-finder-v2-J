package io.asbun.backend.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Emits metrics as CloudWatch Embedded Metric Format (EMF) JSON log lines. CloudWatch reads the
 * ECS awslogs stream, detects the {@code _aws} envelope, and extracts each metric into the
 * {@code RecipeAiFinder/App} namespace — no {@code PutMetricData} call and therefore no extra
 * IAM (design §5.1).
 *
 * <p>Active only when {@code monitoring.metrics.enabled=true}; otherwise {@link NoOpMetricsService}
 * is the bean. Every emit is wrapped so a serialization/logging failure is swallowed and logged
 * at debug — a metrics problem must never propagate into request handling (design §5.4, Req 6.3).
 *
 * <p>Lines are written through a dedicated {@code METRICS} logger (mirroring the {@code AUDIT}
 * logger precedent) so they can be routed/filtered independently if desired.
 *
 * <p>Example line (namespace {@code RecipeAiFinder/App}, dimension {@code Model}):
 * <pre>
 * {"_aws":{"Timestamp":1710000000000,"CloudWatchMetrics":[{"Namespace":"RecipeAiFinder/App",
 *   "Dimensions":[["Model"]],"Metrics":[{"Name":"BedrockLatencyMs","Unit":"Milliseconds"}]}]},
 *   "Model":"claude-haiku","BedrockLatencyMs":812}
 * </pre>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.metrics.enabled", havingValue = "true")
public class EmfMetricsService implements MetricsService {

    /** Dedicated logger for EMF metric lines (see AuditService's "AUDIT" logger precedent). */
    private static final Logger METRICS_LOG = LoggerFactory.getLogger("METRICS");

    private static final String UNIT_MILLIS = "Milliseconds";
    private static final String UNIT_COUNT = "Count";
    private static final String UNIT_NONE = "None";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmfMetricsService() {
        log.info("EMF metrics enabled — emitting to the METRICS logger under namespace {}", NAMESPACE);
    }

    @Override
    public void latencyMs(String metricName, long millis, String dimensionName, String dimensionValue) {
        emit(metricName, millis, UNIT_MILLIS, dimensionName, dimensionValue);
    }

    @Override
    public void count(String metricName, double value, String dimensionName, String dimensionValue) {
        emit(metricName, value, UNIT_COUNT, dimensionName, dimensionValue);
    }

    @Override
    public void gauge(String metricName, double value, String dimensionName, String dimensionValue) {
        emit(metricName, value, UNIT_NONE, dimensionName, dimensionValue);
    }

    /**
     * Builds and logs one EMF line. Never throws: any failure is caught and logged at debug so a
     * metrics problem cannot affect the caller.
     */
    private void emit(String metricName, double value, String unit,
                      String dimensionName, String dimensionValue) {
        try {
            boolean hasDim = dimensionName != null && !dimensionName.isBlank()
                    && dimensionValue != null && !dimensionValue.isBlank();

            ObjectNode root = objectMapper.createObjectNode();

            ObjectNode aws = root.putObject("_aws");
            aws.put("Timestamp", System.currentTimeMillis());

            ArrayNode cwMetrics = aws.putArray("CloudWatchMetrics");
            ObjectNode directive = cwMetrics.addObject();
            directive.put("Namespace", NAMESPACE);

            ArrayNode dimensions = directive.putArray("Dimensions");
            ArrayNode dimensionSet = dimensions.addArray();
            if (hasDim) {
                dimensionSet.add(dimensionName);
            }

            ArrayNode metrics = directive.putArray("Metrics");
            ObjectNode metricDef = metrics.addObject();
            metricDef.put("Name", metricName);
            metricDef.put("Unit", unit);

            // Metric value + dimension value live at the root of the EMF object.
            if (hasDim) {
                root.put(dimensionName, dimensionValue);
            }
            root.put(metricName, value);

            METRICS_LOG.info(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            // A metrics failure must never propagate. Debug-level so it doesn't spam logs.
            log.debug("Failed to emit metric {}: {}", metricName, e.getMessage());
        }
    }
}
