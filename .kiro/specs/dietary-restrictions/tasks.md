# Implementation Plan: Dietary Restrictions

## Overview

This plan implements dietary restriction management across the backend (Spring Boot + DynamoDB) and frontend (Next.js App Router). Tasks are ordered to build foundational data model changes first, then API endpoints, then frontend UI, and finally AI prompt integration. Property-based tests validate correctness properties defined in the design.

## Tasks

- [x] 1. Backend data model and enum setup
  - [x] 1.1 Create the DietaryRestriction enum
    - Create `backend/src/main/java/io/asbun/backend/model/enums/DietaryRestriction.java`
    - Define all 10 enum values (GLUTEN_FREE, KETO, VEGAN, VEGETARIAN, DAIRY_FREE, NUT_FREE, PALEO, LOW_CARB, HALAL, KOSHER) with displayName field and getter
    - _Requirements: 1.3_

  - [x] 1.2 Update the User model with dietaryRestrictions field
    - Add `@Builder.Default private List<String> dietaryRestrictions = new ArrayList<>()` to `backend/src/main/java/io/asbun/backend/model/User.java`
    - Ensure the DynamoDB enhanced client serializes the list attribute correctly
    - _Requirements: 1.1, 1.2, 1.4_

  - [x] 1.3 Update UserDto to include dietaryRestrictions
    - Add `private List<String> dietaryRestrictions` field to `backend/src/main/java/io/asbun/backend/dto/UserDto.java`
    - _Requirements: 1.5_

  - [x] 1.4 Create UpdateDietaryRestrictionsRequest DTO
    - Create `backend/src/main/java/io/asbun/backend/dto/UpdateDietaryRestrictionsRequest.java`
    - Add `@NotNull @Size(max = 10) private List<String> restrictions` field with Lombok @Data
    - _Requirements: 2.1, 1.4_

- [x] 2. Backend API endpoints for dietary restrictions
  - [x] 2.1 Add GET /api/account/dietary-restrictions endpoint
    - Add endpoint to `AccountController` that retrieves the authenticated user's dietaryRestrictions list
    - Return HTTP 200 with the list; return HTTP 404 if user not found
    - _Requirements: 2.2, 2.6_

  - [x] 2.2 Add PUT /api/account/dietary-restrictions endpoint
    - Add endpoint to `AccountController` that accepts `UpdateDietaryRestrictionsRequest`
    - Validate each restriction value against `DietaryRestriction.valueOf()`; return 400 with invalid values if any fail
    - Deduplicate the list using `stream().distinct()`
    - Save to user's profile via `userRepository.save(user)` and return HTTP 200 with saved list
    - Return 404 if user not found
    - _Requirements: 2.1, 2.3, 2.4, 2.5, 2.6_

  - [x] 2.3 Update GET /api/account/profile to include dietaryRestrictions
    - Modify the `getProfile` method in `AccountController` to map `user.getDietaryRestrictions()` into the `UserDto` builder
    - _Requirements: 1.5_

  - [x] 2.4 Write property tests for dietary restrictions API (jqwik)
    - **Property 1: Restriction persistence round-trip** — For any valid subset of predefined restrictions, saving via PUT and retrieving via GET returns the same set (order-independent)
    - **Property 2: Invalid restrictions are rejected without side effects** — For any list with at least one invalid value, PUT returns 400 and stored restrictions remain unchanged
    - **Property 3: Deduplication preserves unique values** — For any list with duplicates, PUT saves a deduplicated list matching the distinct input set
    - **Property 4: Maximum cardinality enforcement** — For any submitted list, stored list contains at most 10 elements
    - **Validates: Requirements 1.1, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5**

  - [x] 2.5 Write unit tests for AccountController dietary restriction endpoints
    - Test GET returns current restrictions
    - Test PUT with valid values saves and returns updated list
    - Test PUT with invalid values returns 400
    - Test PUT with empty list clears restrictions
    - Test 404 when user not found
    - _Requirements: 2.1, 2.2, 2.3, 2.5, 2.6_

- [x] 3. Checkpoint - Backend API verification
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Backend AI prompt integration
  - [x] 4.1 Modify BedrockService to accept dietary restrictions
    - Change `generateRecipes` signature to accept `List<String> dietaryRestrictions` parameter
    - Change `buildPrompt` signature to accept `List<String> dietaryRestrictions` parameter
    - When dietaryRestrictions is non-null and non-empty, append the dietary constraint clause to the prompt using display names
    - When empty or null, do not add any dietary text
    - _Requirements: 6.1, 6.2, 6.4_

  - [x] 4.2 Modify RecipeController to pass dietary restrictions to BedrockService
    - In the `generateRecipes` endpoint, extract `dietaryRestrictions` from the User already loaded for the account status check
    - If user not found, proceed with an empty list
    - Pass restrictions to `bedrockService.generateRecipes(ingredients, dietaryRestrictions, model)`
    - _Requirements: 6.3, 6.5_

  - [x] 4.3 Write property tests for prompt construction (jqwik)
    - **Property 5: Prompt includes all restrictions when present** — For any non-empty valid restrictions list and any non-empty ingredients list, the prompt contains the display name of every restriction
    - **Property 6: Prompt excludes dietary text when restrictions are absent** — For any empty/null restrictions list, the prompt does not contain dietary constraint clause text
    - **Validates: Requirements 6.1, 6.2**

  - [x] 4.4 Write unit tests for BedrockService prompt construction
    - Test prompt with specific restriction combinations contains expected text
    - Test prompt with empty/null restrictions does not contain dietary clause
    - _Requirements: 6.1, 6.2_

- [x] 5. Checkpoint - Backend integration verification
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Frontend account page restructure
  - [x] 6.1 Create account layout with sidebar navigation
    - Create `frontend/app/(protected)/account/layout.tsx`
    - Render a sidebar nav with links to `/account/settings` and `/account/dietary`
    - Use `<nav>` element with `aria-label="Account navigation"`
    - Highlight active link with distinct styling (background color and font weight)
    - Ensure links are keyboard-focusable
    - _Requirements: 3.1, 3.5, 3.6, 3.7_

  - [x] 6.2 Move existing account settings to sub-route
    - Create `frontend/app/(protected)/account/settings/page.tsx` with the existing account page content
    - Modify `frontend/app/(protected)/account/page.tsx` to redirect to `/account/settings` using Next.js `redirect()`
    - _Requirements: 3.2, 3.4_

- [x] 7. Frontend dietary restrictions page
  - [x] 7.1 Create the dietary restrictions page component
    - Create `frontend/app/(protected)/account/dietary/page.tsx` as a client component
    - Fetch current restrictions on mount via GET `/api/account/dietary-restrictions`
    - Display all 10 predefined restrictions as toggle chip elements in a flex-wrap layout
    - Pre-select chips matching the user's saved restrictions
    - Track selected state locally
    - _Requirements: 4.1, 4.2, 4.5_

  - [x] 7.2 Implement save functionality and feedback states
    - Add "Save" button that sends PUT to `/api/account/dietary-restrictions` with selected values
    - Show success toast for at least 3 seconds on successful save
    - Show error message on save failure; retain unsaved selections
    - Show loading spinner during fetch/save and disable Save button
    - Show error with retry option if initial GET fails
    - _Requirements: 4.3, 4.4, 4.6, 4.7_

- [x] 8. Frontend dashboard restrictions banner
  - [x] 8.1 Add dietary restriction badges to dashboard
    - Modify `frontend/app/(protected)/dashboard/page.tsx` to fetch user profile on mount via GET `/api/account/profile`
    - If `dietaryRestrictions` is non-empty, render a banner above the ingredient form with badge chips for each restriction
    - Include an "Edit" link/button navigating to `/account/dietary`
    - If empty, render nothing for the restrictions section
    - Do not display restrictions section while profile is loading
    - Re-fetch profile on navigation back to reflect updates
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 8.2 Write unit tests for frontend components
    - Test dietary restrictions page renders all 10 chips, toggles selections, shows loading/error states
    - Test account layout renders sidebar with correct active states
    - Test dashboard badges render when restrictions exist and hide when empty
    - _Requirements: 3.1, 4.1, 5.1, 5.2_

- [x] 9. Final checkpoint - Full integration verification
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The backend uses Java (Spring Boot, jqwik for PBT) and the frontend uses TypeScript (Next.js with Tailwind CSS)
- The DietaryRestriction enum stores display names for prompt construction; DynamoDB stores enum names as strings

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.4"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1", "2.2", "2.3"] },
    { "id": 3, "tasks": ["2.4", "2.5"] },
    { "id": 4, "tasks": ["4.1"] },
    { "id": 5, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 6, "tasks": ["6.1"] },
    { "id": 7, "tasks": ["6.2", "7.1"] },
    { "id": 8, "tasks": ["7.2", "8.1"] },
    { "id": 9, "tasks": ["8.2"] }
  ]
}
```
