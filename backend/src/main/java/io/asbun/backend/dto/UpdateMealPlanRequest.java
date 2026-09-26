package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Update plan-level attributes (name, dates, servings). All fields are supplied; the service
 * applies them to the plan and bumps {@code updatedAt}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateMealPlanRequest {

    @NotBlank
    @Size(max = 120)
    private String name;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be an ISO date (yyyy-MM-dd)")
    private String startDate;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be an ISO date (yyyy-MM-dd)")
    private String endDate;

    @Positive
    @Max(50)
    private Integer servings;
}
