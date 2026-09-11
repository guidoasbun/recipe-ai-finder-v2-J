package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.asbun.backend.model.enums.MealSlot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Move/edit an existing entry: change its date, slot, and/or servings override. Fields left
 * null are unchanged. The recipe reference is not changed here (remove + add to re-point).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateEntryRequest {

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be an ISO date (yyyy-MM-dd)")
    private String date;

    private MealSlot slot;

    @Positive
    @Max(50)
    private Integer servings;
}
