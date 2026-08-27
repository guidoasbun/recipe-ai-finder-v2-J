package io.asbun.backend.model.enums;

public enum DietaryRestriction {
    GLUTEN_FREE("Gluten-Free"),
    KETO("Keto"),
    VEGAN("Vegan"),
    VEGETARIAN("Vegetarian"),
    DAIRY_FREE("Dairy-Free"),
    NUT_FREE("Nut-Free"),
    PALEO("Paleo"),
    LOW_CARB("Low-Carb"),
    HALAL("Halal"),
    KOSHER("Kosher");

    private final String displayName;

    DietaryRestriction(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
