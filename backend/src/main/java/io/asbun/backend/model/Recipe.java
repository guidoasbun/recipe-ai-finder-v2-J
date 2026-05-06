package io.asbun.backend.model;

import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class Recipe {

    private String recipeId;
    private String userId;
    private String title;
    private String description;
    private String imageUrl;
    private Integer imageWidth;
    private Integer imageHeight;
    private String imageType;
    private Long imageSizeBytes;
    private Long imageGenerationMs;
    private List<String> ingredients;
    private List<String> steps;
    private BedrockModel model;
    private ImageModel imageModel;
    private Instant createdAt;

    @DynamoDbPartitionKey
    public String getRecipeId() {
        return recipeId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames  = "userId-index")
    public String getUserId() {
        return userId;
    }
}
