package io.asbun.backend.dto;

import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SaveRecipeRequest {

    private String imageUrl;

    private ImageModel imageModel = ImageModel.STABILITY_CORE;

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotNull
    private List<String> ingredients;

    @NotNull
    private List<String> steps;

    @NotNull
    private BedrockModel model;
}
