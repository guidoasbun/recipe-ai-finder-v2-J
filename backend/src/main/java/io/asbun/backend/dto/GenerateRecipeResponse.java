package io.asbun.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRecipeResponse {
    
    private String title;
    private String description;
    private List<String> ingredients;
    private List<String> steps;
}
