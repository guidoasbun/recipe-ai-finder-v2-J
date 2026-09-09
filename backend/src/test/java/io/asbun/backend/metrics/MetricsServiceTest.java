package io.asbun.backend.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MetricsServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Logger metricsLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        metricsLogger = (Logger) LoggerFactory.getLogger("METRICS");
        appender = new ListAppender<>();
        appender.start();
        metricsLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        metricsLogger.detachAppender(appender);
    }

    // ── NoOp: default bean does nothing, logs nothing ──────────────────────────
    @Test
    void noOpServiceEmitsNothing() {
        MetricsService metrics = new NoOpMetricsService();

        metrics.latencyMs("BedrockLatencyMs", 812, "Model", "claude-haiku");
        metrics.count("BedrockFailure");
        metrics.gauge("SseActiveEmitters", 3);

        assertThat(appender.list).isEmpty();
    }

    // ── EMF: correct envelope, namespace, dimension, metric name/unit/value ────
    @Test
    void emfServiceEmitsWellFormedLatencyLine() throws Exception {
        MetricsService metrics = new EmfMetricsService();

        metrics.latencyMs("BedrockLatencyMs", 812, "Model", "claude-haiku");

        assertThat(appender.list).hasSize(1);
        JsonNode root = mapper.readTree(appender.list.get(0).getFormattedMessage());

        JsonNode directive = root.path("_aws").path("CloudWatchMetrics").get(0);
        assertThat(directive.path("Namespace").asText()).isEqualTo("RecipeAiFinder/App");
        assertThat(directive.path("Dimensions").get(0).get(0).asText()).isEqualTo("Model");

        JsonNode metricDef = directive.path("Metrics").get(0);
        assertThat(metricDef.path("Name").asText()).isEqualTo("BedrockLatencyMs");
        assertThat(metricDef.path("Unit").asText()).isEqualTo("Milliseconds");

        assertThat(root.path("Model").asText()).isEqualTo("claude-haiku");
        assertThat(root.path("BedrockLatencyMs").asLong()).isEqualTo(812);
    }

    @Test
    void emfServiceEmitsDimensionlessCountWithEmptyDimensionSet() throws Exception {
        MetricsService metrics = new EmfMetricsService();

        metrics.count("SearchEmbedFallback");

        JsonNode root = mapper.readTree(appender.list.get(0).getFormattedMessage());
        JsonNode directive = root.path("_aws").path("CloudWatchMetrics").get(0);

        // Empty dimension set (a single dimension array with no entries).
        assertThat(directive.path("Dimensions").get(0)).isEmpty();
        assertThat(directive.path("Metrics").get(0).path("Name").asText()).isEqualTo("SearchEmbedFallback");
        assertThat(directive.path("Metrics").get(0).path("Unit").asText()).isEqualTo("Count");
        assertThat(root.path("SearchEmbedFallback").asDouble()).isEqualTo(1.0);
    }

    // ── Failure tolerance: an emit must never throw ────────────────────────────
    @Test
    void emfServiceSwallowsSerializationFailures() {
        MetricsService metrics = new EmfMetricsService();

        // Null metric name would break serialization/JSON; must be swallowed, not thrown.
        assertThatCode(() -> metrics.latencyMs(null, 1, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> metrics.count("ok")).doesNotThrowAnyException();
    }
}
