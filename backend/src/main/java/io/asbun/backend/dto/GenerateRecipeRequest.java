package io.asbun.backend.dto;

import io.asbun.backend.model.enums.BedrockModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GenerateRecipeRequest {

    @NotNull
    @Size(min = 1, max = 30)
    private List<@NotBlank @Size(max = 200) String> ingredients;

    @NotNull
    private BedrockModel model;
}
