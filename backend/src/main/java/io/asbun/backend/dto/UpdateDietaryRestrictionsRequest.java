package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateDietaryRestrictionsRequest {

    @NotNull
    @Size(max = 10)
    private List<String> restrictions;
}
