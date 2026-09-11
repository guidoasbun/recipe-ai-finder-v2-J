package io.asbun.backend.model.enums;

/**
 * The meal slots a recipe can be scheduled into on a given day. Single source of truth for
 * the slot vocabulary; the frontend mirrors these names in one constant (like
 * {@code lib/dietary.ts} mirrors {@code DietaryRestriction}).
 */
public enum MealSlot {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK
}
