package io.asbun.backend.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.dto.ModelStatsDto;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                .filter(r -> !STATS_KEY.equals(r.getRecipeId()))
                .collect(Collectors.toList());
    }

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
