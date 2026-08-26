# Requirements Document

## Introduction

This feature adds dietary restriction management to the Recipe AI Finder application. Users can select predefined dietary restrictions from their account settings, which are stored on their user profile and automatically injected into the AI recipe generation prompt. The account page is restructured into a sidebar/sub-nav layout to accommodate the new dietary restrictions page and future settings sections.

## Glossary

- **Dietary_Restrictions_Page**: The frontend page at `/account/dietary` where users manage their dietary restriction selections.
- **Account_Layout**: The sidebar/sub-nav layout component that wraps account sub-pages and provides navigation between them.
- **Dashboard**: The main page at `/dashboard` where users input ingredients and generate recipes.
- **Restriction_Badge**: A visual chip/badge element displayed on the Dashboard representing an active dietary restriction.
- **User_Model**: The DynamoDB entity representing a user, identified by userId as partition key.
- **BedrockService**: The backend service responsible for constructing AI prompts and invoking Amazon Bedrock models to generate recipes.
- **RecipeController**: The backend REST controller handling recipe generation and CRUD operations.
- **AccountController**: The backend REST controller handling account profile and settings operations.
- **Predefined_Restrictions**: The fixed set of dietary restriction options: Gluten-Free, Keto, Vegan, Vegetarian, Dairy-Free, Nut-Free, Paleo, Low-Carb, Halal, Kosher.

## Requirements

### Requirement 1: Store Dietary Restrictions on User Profile

**User Story:** As a user, I want my dietary restrictions saved to my profile, so that they persist across sessions and are available for recipe generation.

#### Acceptance Criteria

1. THE User_Model SHALL include a `dietaryRestrictions` attribute stored as a list of strings in DynamoDB.
2. WHEN a user has no dietary restrictions selected, THE User_Model SHALL default the `dietaryRestrictions` attribute to an empty list using @Builder.Default.
3. THE User_Model SHALL only accept values from the Predefined_Restrictions set (GLUTEN_FREE, KETO, VEGAN, VEGETARIAN, DAIRY_FREE, NUT_FREE, PALEO, LOW_CARB, HALAL, KOSHER) for the `dietaryRestrictions` attribute.
4. THE User_Model SHALL store at most 10 dietary restriction values in the `dietaryRestrictions` attribute.
5. WHEN a user's profile is retrieved via GET /api/account/profile, THE response SHALL include the `dietaryRestrictions` field in the UserDto.

### Requirement 2: Dietary Restrictions Management API

**User Story:** As a user, I want an API endpoint to update my dietary restrictions, so that the frontend can persist my selections.

#### Acceptance Criteria

1. WHEN a PUT request is received at `/api/account/dietary-restrictions` with a valid list of restrictions, THE AccountController SHALL update the authenticated user's `dietaryRestrictions` attribute and return HTTP 200 with the updated list in the response body.
2. WHEN a GET request is received at `/api/account/dietary-restrictions`, THE AccountController SHALL return HTTP 200 with the authenticated user's current list of dietary restrictions.
3. IF a PUT request contains a restriction value not in the Predefined_Restrictions set, THEN THE AccountController SHALL return HTTP 400 with a response body containing the invalid value(s) and a descriptive error message.
4. IF a PUT request contains duplicate restriction values, THEN THE AccountController SHALL deduplicate the list before saving.
5. WHEN a PUT request contains an empty list, THE AccountController SHALL save an empty list clearing all restrictions and return HTTP 200 with an empty list.
6. IF the authenticated user's record is not found in DynamoDB, THEN THE AccountController SHALL return HTTP 404.

### Requirement 3: Account Page Sidebar Layout

**User Story:** As a user, I want a sidebar navigation on the account page, so that I can easily switch between account settings sections.

#### Acceptance Criteria

1. THE Account_Layout SHALL render a sidebar navigation with links to `/account/settings` and `/account/dietary`.
2. WHEN navigating to `/account/settings`, THE Account_Layout SHALL display the existing account settings content (profile, consent management, data export, account deletion).
3. WHEN navigating to `/account/dietary`, THE Account_Layout SHALL display the Dietary_Restrictions_Page.
4. WHEN navigating to `/account`, THE Account_Layout SHALL redirect to `/account/settings`.
5. THE Account_Layout SHALL distinguish the currently active navigation item from inactive items by applying a visually distinct style (e.g., different background color, font weight, or border).
6. THE Account_Layout SHALL render the sidebar navigation within a nav element with an accessible label, and each link SHALL be keyboard-focusable and operable.
7. WHILE any `/account/*` route is displayed, THE Account_Layout SHALL keep the sidebar navigation visible alongside the page content.

### Requirement 4: Dietary Restrictions Selection Page

**User Story:** As a user, I want a dedicated page to select my dietary restrictions from predefined options, so that I can manage my food preferences.

#### Acceptance Criteria

1. THE Dietary_Restrictions_Page SHALL display all ten Predefined_Restrictions as clickable chip/toggle elements arranged in a grid or flex layout.
2. WHEN the page loads, THE Dietary_Restrictions_Page SHALL fetch the user's current restrictions via GET /api/account/dietary-restrictions and pre-select the matching chips.
3. WHEN the user clicks "Save" after toggling restrictions, THE Dietary_Restrictions_Page SHALL send a PUT request to /api/account/dietary-restrictions with the selected values and display a success message for at least 3 seconds.
4. IF the save request fails, THEN THE Dietary_Restrictions_Page SHALL display an error message and retain the user's unsaved selections in the UI.
5. THE Dietary_Restrictions_Page SHALL allow the user to select zero or more restrictions simultaneously.
6. WHILE the page is fetching data or saving, THE Dietary_Restrictions_Page SHALL display a loading indicator and disable the Save button.
7. IF the initial GET request fails, THEN THE Dietary_Restrictions_Page SHALL display an error message with a retry option.

### Requirement 5: Dashboard Display of Active Restrictions

**User Story:** As a user, I want to see my active dietary restrictions on the dashboard, so that I know which restrictions will apply to generated recipes.

#### Acceptance Criteria

1. WHEN the user has one or more dietary restrictions saved, THE Dashboard SHALL display each active restriction as a Restriction_Badge above the ingredient input form.
2. WHEN the user has no dietary restrictions saved, THE Dashboard SHALL not render the restrictions banner section.
3. THE Dashboard SHALL display an "Edit" button next to the restriction badges that navigates to `/account/dietary`.
4. WHEN the user navigates back to the Dashboard after changing restrictions, THE Dashboard SHALL re-fetch the profile and reflect the updated restrictions without requiring a full page reload.
5. WHILE the profile data is loading, THE Dashboard SHALL not display the restrictions section.

### Requirement 6: AI Prompt Integration with Dietary Restrictions

**User Story:** As a user, I want generated recipes to comply with my dietary restrictions, so that I only receive recipes I can actually eat.

#### Acceptance Criteria

1. WHEN generating recipes for a user with one or more dietary restrictions, THE BedrockService SHALL append a constraint clause to the AI prompt that lists each dietary restriction by name and instructs the AI to exclude any ingredients or preparation methods that violate those restrictions.
2. WHEN generating recipes for a user with no dietary restrictions (empty list or null), THE BedrockService SHALL not include any dietary restriction instructions in the AI prompt.
3. THE RecipeController SHALL retrieve the authenticated user's dietary restrictions from the User_Model via userRepository.findById(userId) before invoking the BedrockService for recipe generation.
4. THE BedrockService.generateRecipes() method SHALL accept a List of String dietary restrictions as a parameter alongside the ingredients list and BedrockModel.
5. IF the authenticated user's record is not found when retrieving dietary restrictions, THEN THE RecipeController SHALL proceed with recipe generation using an empty dietary restrictions list.
