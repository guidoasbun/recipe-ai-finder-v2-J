# Tasks — Saved Recipes: Pagination & Search

Implementation plan. Each task is incremental and test-backed. Requirement references map
to `requirements.md`. Backend precedes frontend so the API contract is real before the UI
consumes it.

- [x] 1. Add saved-recipe search configuration
  - Add `recipes.search.page-size-default` (6) and `recipes.search.page-size-max` (50) to
    `backend/src/main/resources/application.properties`, using the env-override style of
    the existing `catalog.search.page-size-*` entries.
  - _Requirements: 4.5_

- [x] 2. Define the search seam types (OpenSearch-ready abstraction)
  - Add `SavedRecipeSearchQuery` record (`userId`, `text`, `page`, `pageSize`) and
    `SavedRecipeSearchResults` record (`items: List<RecipeDto>`, `page`, `pageSize`,
    `totalMatches`) — mirror `CatalogSearchQuery` / `CatalogSearchResults`.
  - Add `SavedRecipeSearchService` interface with
    `SavedRecipeSearchResults search(SavedRecipeSearchQuery query)`.
  - _Requirements: 4.2, 5.1, 5.3_

- [x] 3. Implement in-app search/filter/sort/paginate
  - Implement `InAppSavedRecipeSearchService` (`@Service`, the default and only bean),
    delegating to the existing `RecipeService` mapping so `toDto` (S3 presign + lazy image
    regeneration) is reused and runs only for the returned page.
  - Logic: `findByUserId` → case-insensitive filter on title/description/ingredients when
    `text` is non-blank → sort by `createdAt` desc (nulls last) → compute `totalMatches`
    → slice `[page*pageSize, ...]` (empty when out of range) → map page to `RecipeDto`.
  - _Requirements: 1.4, 1.5, 3.2, 3.6, 5.2, 6.3_

- [x] 4. Wire the paginated/searchable endpoint
  - Update `RecipeController.getRecipes` to accept `q` (`@Size(max=200)`), `page`
    (default 0, clamped `>=0`), and `pageSize` (default from config, clamped `[1,max]`),
    build a `SavedRecipeSearchQuery` scoped to the JWT `userId`, and return
    `SavedRecipeSearchResults`. Inject `defaultPageSize`/`maxPageSize` via `@Value` like
    `CatalogController`.
  - Leave `POST`, `GET /{id}`, image stream, `DELETE`, and `generate` unchanged.
  - _Requirements: 1.1, 3.3, 3.7, 4.1, 4.2, 4.3, 4.4, 4.6, 6.4_

- [x] 5. Backend tests
  - Unit-test the in-app search: title/description/ingredient matches (case-insensitive),
    blank query returns all, newest-first ordering, pagination slicing (first / middle /
    last-partial / out-of-range), and `totalMatches` correctness.
  - Controller test: `page`/`pageSize` clamping (default 6, max cap, negative → 0),
    `q` length validation (400 over 200), response shape, and caller-scoping.
  - Run `./mvnw test` in `backend/` and fix failures.
  - _Requirements: 1.4, 1.5, 3.2, 3.7, 4.1, 4.3, 4.4_

- [x] 6. Frontend: switch Saved Recipes to the paginated/searchable API
  - Per `frontend/AGENTS.md`, review the relevant App Router / data-fetching guide under
    `node_modules/next/dist/docs/` first.
  - Update `frontend/app/(protected)/recipes/page.tsx`: replace the load-all + client sort
    with a fetch of `GET /api/backend/api/recipes?q=&page=`; consume
    `{ items, page, pageSize, totalMatches }`; add `submittedQuery` and `page` state with a
    monotonic request-id ref + `AbortController` (copy the race-safe pattern from
    `browse/page.tsx`). Reset to page 0 on new query.
  - _Requirements: 1.1, 1.4, 3.1, 3.4, 3.6, 3.8_

- [x] 7. Frontend: search input
  - Add the "Search your saved recipes..." input (`maxLength={200}`, `Search` icon, submit
    button) above the grid, matching the catalog markup/classes.
  - _Requirements: 3.1, 3.7_

- [x] 8. Frontend: shared pagination control (top + bottom)
  - Extract a `RecipesPagination` component/render used **above and below** the grid,
    driven by the single `page` state: Previous (disabled on first page), clickable
    numbered pages with the current page marked `aria-current="page"` and ellipsis
    condensing for large counts, Next (disabled on last page). Render only when
    `totalPages > 1`. Ensure keyboard accessibility and accessible names.
  - _Requirements: 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

- [x] 9. Frontend: empty / no-results / loading states
  - Preserve the loading spinner and error+retry box. Keep the existing "No saved recipes
    yet." empty state (no query, `totalMatches === 0`, no pagination). Add a distinct "No
    recipes match your search." state when a query is active with zero matches.
  - _Requirements: 1.5, 1.6, 3.5, 3.6_

- [x] 10. Frontend tests and verification
  - Add/adjust tests per repo conventions: renders ≤6 cards per page, top and bottom
    controls stay in sync, page-number navigation works, search resets to page 1 and
    filters, and both empty states render. Run the frontend lint/test/build and fix
    failures.
  - _Requirements: 1.1, 2.7, 3.2, 3.4, 3.5_

- [x] 11. Manual verification pass
  - With a seeded account of >6 saved recipes: confirm 6 per page, top/bottom controls in
    sync, direct page-number jumps, "Page X of Y" accuracy, search filtering + page reset,
    and both empty states. Confirm AI generation, catalog search, save, and delete are
    unaffected.
  - _Requirements: 1.1, 1.3, 2.1, 2.7, 3.2, 6.2, 6.4_
```

Notes:
- No DynamoDB schema/GSI change and no new infrastructure (Requirement 6). All filtering,
  sorting, and pagination happen in the service layer over the user's GSI-queried items.
- OpenSearch is intentionally not used for saved recipes; the `SavedRecipeSearchService`
  interface is the seam for a future OpenSearch implementation (Requirement 5), reusing
  the existing `OpenSearchConfig` Oracle-node transport if ever warranted.
