package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaveRecipeRequest {

    @URL
    private String imageUrl;

    private ImageModel imageModel = ImageModel.STABILITY_CORE;

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 2000)
    private String description;

    @NotNull
    @Size(min = 1, max = 50)
    private List<@NotBlank @Size(max = 500) String> ingredients;

    @NotNull
    @Size(min = 1, max = 50)
    private List<@NotBlank @Size(max = 1000) String> steps;

    @NotNull
    private BedrockModel model;

    private Long textGenerationMs;
}
