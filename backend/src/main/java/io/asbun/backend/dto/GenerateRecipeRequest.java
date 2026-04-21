package io.asbun.backend.dto;

import io.asbun.backend.model.enums.BedrockModel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class GenerateRecipeRequest {
    
    @NotNull
    private List<String> ingredients;

    @NotNull
    private BedrockModel model;
}
