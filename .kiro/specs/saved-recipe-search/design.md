# Design — Saved Recipes: Pagination & Search

## Context

The Saved Recipes page (`frontend/app/(protected)/recipes/page.tsx`) calls
`GET /api/recipes`, which returns the full `List<RecipeDto>` for the authenticated user;
sorting and rendering happen entirely in the browser. There is no server-side pagination
or search.

The app already ships a paginated, searchable **catalog** feature that we mirror:

- Frontend reference: `frontend/app/(protected)/browse/page.tsx` — search box, Prev/Next,
  "Page X of Y", `totalMatches`, and race-safe fetch via a monotonic request id +
  `AbortController`.
- Backend reference: `CatalogController.search` (`GET /api/catalog/search`), the
  `CatalogSearchService` interface, and the `CatalogSearchResults(items, page, pageSize,
  totalMatches)` record.

Backend stack: Spring Boot + AWS DynamoDB (Enhanced Client). Recipes live in the table
named by `dynamodb.recipes-table`; a user's recipes are fetched via the `userId-index`
GSI in `RecipeRepository.findByUserId`. Frontend: Next.js (App Router) — note
`frontend/AGENTS.md`: this is a modified Next.js; consult
`node_modules/next/dist/docs/` before writing frontend code.

---

## OpenSearch decision (the user's open question)

**Question:** "Can we implement OpenSearch for this feature? We already have it running on
an Oracle instance."

**Answer: It's feasible, and the client plumbing already exists — but it is not the right
tool for *saved* recipes right now. We build in-app search and leave an OpenSearch-ready
seam.**

What already exists:

- `OpenSearchConfig` builds an `OpenSearchClient` and already supports a **`basic`-auth
  over HTTPS** transport explicitly written for "a self-hosted OpenSearch node (e.g. the
  Oracle Cloud free-tier box)", plus a SigV4 transport for Amazon OpenSearch.
- `OpenSearchProperties` (`opensearch.*`), the `opensearch-java` dependency, and an
  `OpenSearchCatalogSearchService` are all present.
- That integration is `@ConditionalOnProperty(catalog.search.backend=opensearch)` and
  indexes the **shared catalog** (`opensearch.index=catalog-recipes`, ~2.2M docs).

Why in-app is the right call for *saved* recipes:

| Factor | Saved recipes (this feature) | Shared catalog (why *it* uses OpenSearch) |
|---|---|---|
| Cardinality | Per user: tens to low-hundreds | Global: ~2.2M rows |
| Search need | Substring filter on a tiny set | Keyword + semantic (kNN) over millions |
| Freshness | Must reflect a save/delete instantly | Rebuilt by an ingestion pipeline |
| Cost/ops of an index | Not justified | Justified by scale |

Filtering a few dozen items already in memory is instant and always fresh. Putting
per-user documents into the Oracle OpenSearch node would add: per-user documents,
owner-scoping on every query, and index-sync on every save/delete — real complexity for
no user-visible gain at this scale.

**Seam, not lock-in.** We introduce a `SavedRecipeSearchService` abstraction (parallel to
`CatalogSearchService`). The default in-app implementation queries DynamoDB and
filters/sorts/paginates in memory. If a user's collection ever grows large enough to
matter, an `OpenSearchSavedRecipeSearchService` can be added later behind the same
interface and selected by a property — reusing the existing `OpenSearchConfig` (including
the Oracle-node `basic`-auth transport) — with **no controller or frontend change**.

---

## Architecture overview

```
Saved Recipes page (recipes/page.tsx)
  │  fetch GET /api/backend/api/recipes?q=&page=&pageSize=
  ▼
RecipeController.getRecipes(q, page, pageSize, auth)
  │  clamp page/pageSize, build SavedRecipeSearchQuery(userId, q, page, pageSize)
  ▼
SavedRecipeSearchService  (interface — the swap seam)
  └─ InAppSavedRecipeSearchService  (default)
        │  recipeRepository.findByUserId(userId)      → List<Recipe> (this user only)
        │  filter by q (title/description/ingredients, case-insensitive)
        │  sort by createdAt desc
        │  paginate (page, pageSize)
        │  map page items → RecipeDto (existing RecipeService.toDto logic)
        ▼
     SavedRecipeSearchResults(items, page, pageSize, totalMatches)
```

No DynamoDB schema or index change. The GSI query already returns exactly the caller's
items; all new logic is filter/sort/paginate in the service layer.

---

## Backend design

### API contract

Extend the existing endpoint (no new route, keep it backward-tolerant):

```
GET /api/recipes?q={text}&page={0-based}&pageSize={n}
```

- `q` — optional, `@Size(max = 200)`. Blank/whitespace ⇒ no filter.
- `page` — optional, default `0`, clamped to `>= 0`.
- `pageSize` — optional, default from `recipes.search.page-size-default` (6), clamped to
  `[1, recipes.search.page-size-max]`.

Response body (mirrors `CatalogSearchResults`):

```json
{
  "items": [ /* RecipeDto, one page */ ],
  "page": 0,
  "pageSize": 6,
  "totalMatches": 27
}
```

> **Contract change note.** `GET /api/recipes` currently returns a bare
> `List<RecipeDto>`; it will now return the paginated object above. The only consumer is
> the Saved Recipes page, which is updated in the same change (Task list keeps them
> together). No other caller reads this endpoint (verified: only `recipes/page.tsx`).

### New types

- `SavedRecipeSearchQuery` (record): `String userId, String text, int page, int pageSize`.
- `SavedRecipeSearchResults` (record): `List<RecipeDto> items, int page, int pageSize,
  long totalMatches` — same shape as `CatalogSearchResults`.
- `SavedRecipeSearchService` (interface): `SavedRecipeSearchResults search(SavedRecipeSearchQuery query)`.
- `InAppSavedRecipeSearchService` (`@Service`, default): implements the interface.

### InAppSavedRecipeSearchService logic

1. `List<Recipe> all = recipeRepository.findByUserId(query.userId())` — already scoped to
   the user via the GSI.
2. **Filter:** if `text` is non-blank, keep recipes where lowercased `title`,
   `description`, or any `ingredients` entry contains the lowercased query. (Simple
   `contains` substring match — consistent with a small in-memory set; the in-app catalog
   search uses the same spirit of token/substring matching.)
3. **Sort:** by `createdAt` descending (nulls last), replicating the current client-side
   sort so ordering is unchanged for users.
4. **Total:** `totalMatches = filtered.size()`.
5. **Paginate:** `from = page * pageSize`; if `from >= size`, return an empty items list
   (still report the real `totalMatches` and echoed `page`/`pageSize`). Otherwise slice
   `[from, min(from + pageSize, size))`.
6. **Map:** convert only the page's `Recipe` items to `RecipeDto`.

**Where DTO mapping lives.** `RecipeService.toDto` is currently private and performs S3
presigned-URL generation and lazy image regeneration. To avoid duplicating that logic and
to keep the paid image-regeneration side effects running for *only the items actually
returned* (a page), the search service will delegate mapping back through
`RecipeService`. Approach: `RecipeService` exposes the paginated search (it already owns
`RecipeRepository` and `toDto`), and the `SavedRecipeSearchService` interface is the seam
the controller depends on, implemented by (or delegating to) `RecipeService`'s logic.

Concretely:

- Keep `toDto` in `RecipeService`.
- Add `SavedRecipeSearchResults searchRecipesByUser(SavedRecipeSearchQuery query)` on the
  in-app service that filters/sorts/paginates the `Recipe` list, then maps the page via
  `toDto`.
- The controller depends on the `SavedRecipeSearchService` interface; the in-app bean is
  the only implementation wired today.

> **Performance note.** Mapping only the returned page (not the whole list) is a
> side-benefit: today `getRecipesByUser` maps *every* recipe to a DTO, which calls
> `s3Service.objectExists` per recipe. Paginating means we only do that S3 work for the ~6
> recipes on the current page.

### Controller change

`RecipeController.getRecipes` gains `@RequestParam` args mirroring `CatalogController`:

```java
@GetMapping
public ResponseEntity<SavedRecipeSearchResults> getRecipes(
        @RequestParam(required = false) @Size(max = 200) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Integer pageSize,
        Authentication authentication) {
    int size = pageSize == null ? defaultPageSize : pageSize;
    size = Math.max(1, Math.min(size, maxPageSize));
    int safePage = Math.max(0, page);
    var query = new SavedRecipeSearchQuery(getUserId(authentication),
            q, safePage, size);
    return ResponseEntity.ok(savedRecipeSearchService.search(query));
}
```

`defaultPageSize` / `maxPageSize` come from new properties (below). `@Validated` is
already on the controller, so `@Size` is enforced.

### Configuration (application.properties)

```properties
# Saved-recipe search/pagination (Saved Recipes page)
recipes.search.page-size-default=${RECIPES_PAGE_SIZE_DEFAULT:6}
recipes.search.page-size-max=${RECIPES_PAGE_SIZE_MAX:50}
```

Named to match the existing `catalog.search.page-size-*` convention.

---

## Frontend design

Rework `frontend/app/(protected)/recipes/page.tsx` to mirror `browse/page.tsx`, adapted
to saved recipes. (Per `frontend/AGENTS.md`, review the App Router / data-fetching guide
under `node_modules/next/dist/docs/` before editing.)

### State

- `query` / `submittedQuery` — search box value vs. the submitted term driving fetches.
- `page` — 0-based current page (single source of truth shared by top and bottom controls).
- `results: SavedRecipeSearchResults | null` — `{ items, page, pageSize, totalMatches }`.
- `loading`, `error`.
- `requestSeq` ref + `AbortController` ref — ignore superseded/stale responses (copied
  from `browse/page.tsx`).

### Fetch

```
GET /api/backend/api/recipes?q={submittedQuery}&page={page}
```

- Server owns sorting/pagination now; remove the client-side `sort`.
- `totalPages = Math.max(1, Math.ceil(totalMatches / pageSize))`.
- Submitting the search resets `page` to 0 (Req 3.4).

### Components / rendering

- **Search form:** input with placeholder "Search your saved recipes...", `maxLength={200}`,
  a `Search` icon and submit button — same markup/classes as `browse/page.tsx`.
- **Grid:** unchanged — `RecipeCard` in a `grid gap-6 sm:grid-cols-2 lg:grid-cols-3`.
  With a page size of 6, the grid fills up to 2 rows on desktop.
- **Pagination control (new shared piece):** rendered **above and below** the grid.
  Extract a small `RecipesPagination` component (or a local render function) so the two
  placements stay in sync and there is one implementation:
  - **Previous** button — disabled when `page === 0`.
  - **Numbered pages** — clickable buttons `1..totalPages`; the current page is styled
    active and carries `aria-current="page"`. For large counts, condense with ellipses
    (e.g. show first, last, and a window around the current page) so the row stays
    bounded (Req 2.6).
  - **Next** button — disabled when `page + 1 >= totalPages`.
  - All controls are real `<button>`s (keyboard-accessible) with accessible names.
- **States:**
  - Loading → spinner (`Loader2`), as today.
  - Error → retry box, as today.
  - `totalMatches === 0` and no query → existing empty state, **no pagination**.
  - `totalMatches === 0` and a query is active → "No recipes match your search." state,
    **no pagination**.
- Pagination controls render only when `totalPages > 1`.

### Accessibility

- Numbered page buttons: accessible name like "Go to page 3"; current page
  `aria-current="page"`.
- Prev/Next: clear button text or `aria-label`; disabled state via the `disabled`
  attribute so it is announced and non-focusable-activatable.
- Search input already labeled via `aria-label` in the catalog pattern; reuse it.

---

## Error handling

- **Backend:** invalid `q` (>200 chars) → 400 via bean validation (same as catalog).
  Out-of-range `page` → empty items with correct `totalMatches` (no error). Auth failures
  handled by existing security config.
- **Frontend:** non-OK response → error state with Retry. Aborted (superseded) requests
  are ignored, not surfaced as errors (matches `browse/page.tsx`).

---

## Testing strategy

- **Backend unit tests** for `InAppSavedRecipeSearchService` / the search method:
  - filter matches on title, description, and ingredients (case-insensitive);
  - blank/whitespace query returns all;
  - newest-first ordering by `createdAt`;
  - pagination slicing: first page, middle page, last partial page, out-of-range page
    (empty items, correct `totalMatches`);
  - `pageSize`/`page` clamping at the controller (default 6, max cap, negative page → 0).
- **Backend controller test:** `GET /api/recipes?q=&page=&pageSize=` returns the
  page-response shape and only the caller's recipes.
- **Frontend:** verify the page renders a page of ≤6 cards, top+bottom controls stay in
  sync, page numbers navigate, search resets to page 1 and filters, and the two empty
  states (no saved recipes vs. no search matches) render correctly. Follow existing
  frontend test conventions in the repo.
- Run the backend build/tests (`./mvnw test` in `backend/`) and the frontend
  lint/test/build after changes; fix any failures before considering the work done.

---

## Out of scope

- Any OpenSearch-backed implementation for saved recipes (seam only; see decision above).
- Server-side sort options beyond newest-first (current behavior preserved).
- Changes to the shared catalog search, AI generation, or dietary-restriction features.
- DynamoDB schema / GSI changes.
