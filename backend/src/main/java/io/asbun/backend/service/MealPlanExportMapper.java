package io.asbun.backend.service;

import io.asbun.backend.dto.DataExportJson;
import io.asbun.backend.model.MealPlan;
import io.asbun.backend.model.MealPlanEntry;
import io.asbun.backend.model.RecipeRef;

import java.util.List;

/**
 * Maps {@link MealPlan}s to their data-export shape. Shared by {@link DataExportService}
 * (JSON) and {@link DataExportAsyncWorker} (ZIP) so both exports include meal plans
 * identically. Entries carry recipe references by id only — recipe content is exported
 * separately under {@code recipes}.
 */
final class MealPlanExportMapper {

    private MealPlanExportMapper() {
    }

    static List<DataExportJson.MealPlanExportData> map(List<MealPlan> plans) {
        return plans.stream()
                .map(plan -> DataExportJson.MealPlanExportData.builder()
                        .mealPlanId(plan.getMealPlanId())
                        .name(plan.getName())
                        .startDate(plan.getStartDate())
                        .endDate(plan.getEndDate())
                        .servings(plan.getServings())
                        .createdAt(plan.getCreatedAt())
                        .updatedAt(plan.getUpdatedAt())
                        .entries(mapEntries(plan.getEntries()))
                        .build())
                .toList();
    }

    private static List<DataExportJson.MealPlanEntryExportData> mapEntries(List<MealPlanEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(e -> {
                    RecipeRef ref = e.getRecipeRef();
                    return DataExportJson.MealPlanEntryExportData.builder()
                            .entryId(e.getEntryId())
                            .date(e.getDate())
                            .slot(e.getSlot() != null ? e.getSlot().name() : null)
                            .servings(e.getServings())
                            .recipeSource(ref != null && ref.getSource() != null ? ref.getSource().name() : null)
                            .recipeId(ref != null ? ref.getRecipeId() : null)
                            .build();
                })
                .toList();
    }
}
