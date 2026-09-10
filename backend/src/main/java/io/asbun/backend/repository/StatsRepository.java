package io.asbun.backend.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.dto.ModelStatsDto;
import io.asbun.backend.dto.StatsAggregate;
import io.asbun.backend.dto.StatsAggregate.Bucket;
import io.asbun.backend.model.Recipe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class StatsRepository {

    private static final String STATS_KEY = "STATS#MODEL_AVERAGES";
    private static final String AGGREGATE_KEY = "STATS#MODEL_AGGREGATE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Attribute-name prefixes for the numeric aggregate item. Each metric is stored as a pair of
    // Number attributes (a running sum and a sample count) that we mutate with atomic ADD, so
    // concurrent increments never race. The key is embedded in the attribute name.
    private static final String TEXT_SUM = "text_sum_";
    private static final String TEXT_CNT = "text_cnt_";
    private static final String IMG_SUM = "img_sum_";
    private static final String IMG_CNT = "img_cnt_";
    private static final String DAY_SUM = "day_sum_";
    private static final String DAY_CNT = "day_cnt_";
    private static final String UPDATED_AT = "updatedAt";

    private final DynamoDbTable<Recipe> table;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public StatsRepository(DynamoDbEnhancedClient enhancedClient,
                           DynamoDbClient dynamoDbClient,
                           @Value("${dynamodb.recipes-table}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(Recipe.class));
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public List<Recipe> scanAllRecipes() {
        return table.scan()
                .stream()
                .flatMap(page -> page.items().stream())
                .filter(r -> !STATS_KEY.equals(r.getRecipeId()) && !AGGREGATE_KEY.equals(r.getRecipeId()))
                .collect(Collectors.toList());
    }

    /**
     * Atomically folds one text-generation latency sample into the aggregate. Single UpdateItem,
     * server-side ADD — no read-modify-write, no locking.
     */
    public void incrementTextModel(String modelName, long ms, String updatedAt) {
        applyIncrements(
                Map.of(TEXT_SUM + modelName, ms),
                Map.of(TEXT_CNT + modelName, 1L),
                updatedAt);
    }

    /**
     * Atomically folds one image-generation latency sample into both the per-model bucket and the
     * given day's bucket, in a single UpdateItem.
     */
    public void incrementImageModel(String modelName, String isoDate, long ms, String updatedAt) {
        String dayKey = dateToDayKey(isoDate);
        applyIncrements(
                Map.of(IMG_SUM + modelName, ms, DAY_SUM + dayKey, ms),
                Map.of(IMG_CNT + modelName, 1L, DAY_CNT + dayKey, 1L),
                updatedAt);
    }

    /**
     * Atomically folds a set of latency deltas into the aggregate item in a single UpdateItem.
     * Every attribute in {@code sumDeltas}/{@code countDeltas} is applied with an ADD expression,
     * which DynamoDB evaluates server-side — so concurrent writers compose without a read step and
     * without any locking. {@code updatedAt} is stamped with SET in the same request.
     */
    private void applyIncrements(Map<String, Long> sumDeltas,
                                 Map<String, Long> countDeltas,
                                 String updatedAt) {
        if (sumDeltas.isEmpty() && countDeltas.isEmpty()) {
            return;
        }
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        StringBuilder addClause = new StringBuilder();

        int i = 0;
        for (Map.Entry<String, Long> e : sumDeltas.entrySet()) {
            i++;
            String nph = "#s" + i;
            String vph = ":s" + i;
            names.put(nph, e.getKey());
            values.put(vph, AttributeValue.fromN(Long.toString(e.getValue())));
            if (addClause.length() > 0) addClause.append(", ");
            addClause.append(nph).append(" ").append(vph);
        }
        int j = 0;
        for (Map.Entry<String, Long> e : countDeltas.entrySet()) {
            j++;
            String nph = "#c" + j;
            String vph = ":c" + j;
            names.put(nph, e.getKey());
            values.put(vph, AttributeValue.fromN(Long.toString(e.getValue())));
            if (addClause.length() > 0) addClause.append(", ");
            addClause.append(nph).append(" ").append(vph);
        }

        names.put("#u", UPDATED_AT);
        values.put(":u", AttributeValue.fromS(updatedAt));

        String updateExpression = "ADD " + addClause + " SET #u = :u";

        try {
            dynamoDbClient.updateItem(r -> r
                    .tableName(tableName)
                    .key(Map.of("recipeId", AttributeValue.fromS(AGGREGATE_KEY)))
                    .updateExpression(updateExpression)
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values));
        } catch (Exception ex) {
            log.error("Failed to apply atomic stats increments", ex);
        }
    }

    /**
     * Loads the numeric aggregate item and reconstructs a {@link StatsAggregate}. Attribute names
     * are parsed back into their (bucket-type, key) form. Returns empty if the item does not exist
     * yet (first run before any increment or rebuild).
     */
    public Optional<StatsAggregate> loadAggregate() {
        Map<String, AttributeValue> key = Map.of("recipeId", AttributeValue.fromS(AGGREGATE_KEY));
        var response = dynamoDbClient.getItem(r -> r.tableName(tableName).key(key));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        StatsAggregate agg = new StatsAggregate();

        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            String attr = e.getKey();
            AttributeValue v = e.getValue();
            if (UPDATED_AT.equals(attr) && v.s() != null) {
                agg.setUpdatedAt(v.s());
            } else if (attr.startsWith(TEXT_SUM)) {
                bucket(agg.getTextModels(), attr.substring(TEXT_SUM.length())).setSumMs(asDouble(v));
            } else if (attr.startsWith(TEXT_CNT)) {
                bucket(agg.getTextModels(), attr.substring(TEXT_CNT.length())).setCount(asLong(v));
            } else if (attr.startsWith(IMG_SUM)) {
                bucket(agg.getImageModels(), attr.substring(IMG_SUM.length())).setSumMs(asDouble(v));
            } else if (attr.startsWith(IMG_CNT)) {
                bucket(agg.getImageModels(), attr.substring(IMG_CNT.length())).setCount(asLong(v));
            } else if (attr.startsWith(DAY_SUM)) {
                bucket(agg.getDailyImage(), dayKeyToDate(attr.substring(DAY_SUM.length()))).setSumMs(asDouble(v));
            } else if (attr.startsWith(DAY_CNT)) {
                bucket(agg.getDailyImage(), dayKeyToDate(attr.substring(DAY_CNT.length()))).setCount(asLong(v));
            }
        }
        return Optional.of(agg);
    }

    /**
     * Replaces the aggregate item wholesale from a rebuilt {@link StatsAggregate}. Used only by the
     * full-scan rebuild path (first run / recovery), not on the hot write path. A PutItem here is
     * safe because the rebuild computes the authoritative totals from source recipes.
     */
    public void overwriteAggregate(StatsAggregate aggregate) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("recipeId", AttributeValue.fromS(AGGREGATE_KEY));
        aggregate.getTextModels().forEach((model, b) -> {
            item.put(TEXT_SUM + model, AttributeValue.fromN(Long.toString(Math.round(b.getSumMs()))));
            item.put(TEXT_CNT + model, AttributeValue.fromN(Long.toString(b.getCount())));
        });
        aggregate.getImageModels().forEach((model, b) -> {
            item.put(IMG_SUM + model, AttributeValue.fromN(Long.toString(Math.round(b.getSumMs()))));
            item.put(IMG_CNT + model, AttributeValue.fromN(Long.toString(b.getCount())));
        });
        aggregate.getDailyImage().forEach((date, b) -> {
            String dayKey = dateToDayKey(date);
            item.put(DAY_SUM + dayKey, AttributeValue.fromN(Long.toString(Math.round(b.getSumMs()))));
            item.put(DAY_CNT + dayKey, AttributeValue.fromN(Long.toString(b.getCount())));
        });
        if (aggregate.getUpdatedAt() != null) {
            item.put(UPDATED_AT, AttributeValue.fromS(aggregate.getUpdatedAt()));
        }
        dynamoDbClient.putItem(r -> r.tableName(tableName).item(item));
    }

    // --- attribute-name key encoding -------------------------------------------------------

    /** Model enum names are safe as-is; dates have their dashes stripped so the attribute name is clean. */
    private static String dateToDayKey(String isoDate) {
        return isoDate.replace("-", "");
    }

    private static String dayKeyToDate(String dayKey) {
        // yyyyMMdd -> yyyy-MM-dd
        if (dayKey.length() == 8) {
            return dayKey.substring(0, 4) + "-" + dayKey.substring(4, 6) + "-" + dayKey.substring(6, 8);
        }
        return dayKey;
    }

    private static Bucket bucket(Map<String, Bucket> map, String key) {
        return map.computeIfAbsent(key, k -> new Bucket());
    }

    private static double asDouble(AttributeValue v) {
        try {
            return v.n() == null ? 0 : Double.parseDouble(v.n());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long asLong(AttributeValue v) {
        try {
            return v.n() == null ? 0 : Long.parseLong(v.n());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- legacy JSON-blob stats (kept for the older /api/stats cached view) ----------------

    public void saveStats(ModelStatsDto stats) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(stats);
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("recipeId", AttributeValue.fromS(STATS_KEY));
            item.put("statsJson", AttributeValue.fromS(json));
            dynamoDbClient.putItem(r -> r.tableName(tableName).item(item));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stats", e);
        }
    }

    public Optional<ModelStatsDto> loadStats() {
        Map<String, AttributeValue> key = Map.of(
                "recipeId", AttributeValue.fromS(STATS_KEY)
        );
        var response = dynamoDbClient.getItem(r -> r.tableName(tableName).key(key));
        if (!response.hasItem() || !response.item().containsKey("statsJson")) {
            return Optional.empty();
        }
        try {
            String json = response.item().get("statsJson").s();
            return Optional.of(OBJECT_MAPPER.readValue(json, ModelStatsDto.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize stats", e);
            return Optional.empty();
        }
    }
}
