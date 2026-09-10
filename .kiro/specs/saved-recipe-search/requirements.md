# Requirements — Saved Recipes: Pagination & Search

## Overview

The **Saved Recipes** page (`/recipes`) currently loads every recipe a user has saved in
one request and renders them all at once, sorted newest-first in the browser. As a user's
saved collection grows, this becomes slow to load and hard to navigate.

This feature adds two things to the Saved Recipes page:

1. **Pagination** — show a bounded page of recipes (~6 at a time), with page navigation
   controls (Previous/Next plus numbered pages) at both the top and bottom of the list,
   and a visible total page count where each page number is clickable.
2. **Search** — a "Search your saved recipes" input that filters the user's own saved
   recipes by keyword (title / description / ingredients).

This is scoped to a user's **own saved recipes** only. It does not touch the shared
catalog ("Look for Existing Recipes") feature or the AI generation flow.

### Relationship to the existing catalog search

The app already has a paginated, searchable catalog feature (`/browse` →
`GET /api/catalog/search`) built on a swappable `CatalogSearchService` with an in-app
default and an OpenSearch implementation. This spec deliberately **mirrors that
established pattern** — the same page-response shape (`items`, `page`, `pageSize`,
`totalMatches`), the same controller conventions (bounded `page`/`pageSize`, `q` param),
and the same race-safe frontend fetch behavior — so the two features stay consistent and
the code is familiar.

### Decision on OpenSearch (answering the open question)

**Recommendation: do NOT put saved-recipe search on OpenSearch for the initial
implementation. Search saved recipes in-app.**

Reasoning (see `design.md §OpenSearch decision` for the full analysis):

- **Scale doesn't justify it.** Saved recipes are *per-user*. A single user has at most
  tens to low-hundreds of saved recipes. Keyword filtering over a few dozen items in
  memory is instant; an index adds latency (network hop), cost, and operational surface
  for no user-visible benefit.
- **The existing OpenSearch node indexes the shared catalog, not per-user data.** The
  OpenSearch running on the Oracle Cloud instance holds the ~2.2M-row shared *catalog*
  index (`catalog-recipes`). Reusing it for saved recipes would mean adding per-user
  documents, owner-scoping every query, and keeping the index in sync on every
  save/delete — real complexity for a dataset small enough to filter in memory.
- **DynamoDB already returns a user's recipes cheaply.** `findByUserId` queries the
  `userId-index` GSI and returns just that user's items. We sort/filter/paginate that set
  in the service layer.
- **We keep the door open.** As with the catalog, search sits behind a small service
  seam so an OpenSearch-backed implementation can be swapped in later by configuration if
  a user ever accumulates enough recipes to warrant it — without changing the API
  contract or the frontend.

So: **OpenSearch is feasible and the plumbing already exists, but it is not warranted for
this feature now.** This spec builds the in-app path and leaves an OpenSearch-ready seam.

---

## Requirement 1 — Paginated saved-recipes listing

**User story:** As a signed-in user with many saved recipes, I want my saved recipes shown
a page at a time, so that the page loads quickly and is easy to navigate.

### Acceptance criteria

1. WHEN a signed-in user opens the Saved Recipes page THEN the system SHALL display at
   most one page of saved recipes (default page size **6**).
2. WHEN the user has more saved recipes than fit on one page THEN the system SHALL provide
   navigation controls to move between pages.
3. WHEN a page of results is displayed THEN the system SHALL show the current page and the
   total number of pages available (e.g. "Page 2 of 5").
4. WHEN the user requests a page THEN the system SHALL return recipes ordered
   newest-first by `createdAt` (preserving the current sort behavior), consistently across
   pages so no recipe is skipped or duplicated.
5. WHEN the requested page is beyond the available range THEN the system SHALL return an
   empty page without error (and the UI SHALL show an appropriate state).
6. WHEN a user has zero saved recipes THEN the system SHALL show the existing empty state
   ("No saved recipes yet. Generate some first!") and no pagination controls.
7. IF the user is not authenticated THEN the system SHALL apply the same access control
   as today (the endpoint remains authenticated; only the caller's own recipes are ever
   returned).

---

## Requirement 2 — Pagination controls at top and bottom

**User story:** As a user browsing my saved recipes, I want page controls both above and
below the list, so that I can navigate without scrolling back to the top.

### Acceptance criteria

1. WHEN more than one page of recipes exists THEN the system SHALL render pagination
   controls **both above and below** the recipe grid.
2. Each pagination control SHALL include a **Next** button and a **Previous** button.
3. WHEN the user is on the first page THEN the system SHALL disable the Previous control;
   WHEN the user is on the last page THEN the system SHALL disable the Next control.
4. The pagination control SHALL render a **clickable list of page numbers**; clicking a
   page number SHALL navigate directly to that page.
5. WHEN a page is active THEN the system SHALL visually indicate which page number is
   current.
6. WHEN the number of pages is large THEN the page-number list SHALL remain usable (e.g.
   condense with ellipses rather than rendering an unbounded row of numbers).
7. WHEN the user navigates via the top control THEN the bottom control SHALL reflect the
   same current page, and vice versa (a single source of truth for the current page).
8. The pagination controls SHALL be keyboard-accessible and expose accessible labels
   (e.g. buttons with clear names, current page marked with `aria-current`).

---

## Requirement 3 — Search your saved recipes

**User story:** As a user, I want to search my saved recipes by keyword, so that I can find
a specific recipe without paging through all of them.

### Acceptance criteria

1. WHEN the Saved Recipes page loads THEN the system SHALL display a search input labeled
   for searching saved recipes (e.g. placeholder "Search your saved recipes...").
2. WHEN a user submits a non-empty query THEN the system SHALL return only that user's
   saved recipes whose title, description, or ingredients match the query terms
   (case-insensitive).
3. WHEN a query is submitted THEN the results SHALL be paginated using the same page size
   and controls as the unfiltered listing, and the page-count SHALL reflect the number of
   matching recipes.
4. WHEN a new query is submitted THEN the system SHALL reset to the first page of results.
5. WHEN no saved recipes match the query THEN the system SHALL show a "no results" state
   (distinct from the "no saved recipes yet" empty state) and SHALL NOT show an error.
6. WHEN the query is empty or whitespace-only THEN the system SHALL treat it as "no
   filter" and return the full paginated listing.
7. WHEN a query is submitted THEN the system SHALL bound the input length (max 200
   characters) consistent with the existing catalog search validation.
8. WHEN the search results are loading THEN the system SHALL show a loading indicator and
   SHALL NOT let a slower earlier request overwrite the results of a newer request
   (race-safe, matching the catalog search page behavior).

---

## Requirement 4 — API: paginated, searchable saved-recipes endpoint

**User story:** As the frontend, I want the saved-recipes endpoint to accept pagination and
search parameters and return page metadata, so that the UI can render controls and pages.

### Acceptance criteria

1. The system SHALL extend `GET /api/recipes` to accept optional query parameters:
   `q` (search text, max 200), `page` (0-based, default 0), and `pageSize` (default 6,
   bounded by a configured maximum).
2. WHEN the endpoint is called THEN the system SHALL return a page-response body
   containing the page of `items`, the `page`, the `pageSize`, and the `totalMatches`
   (total number of recipes matching the query for that user).
3. The endpoint SHALL clamp `page` to `>= 0` and `pageSize` to `[1, max]` using configured
   defaults, mirroring the catalog controller's bounds handling.
4. The endpoint SHALL only ever operate over the authenticated caller's own recipes
   (scoped by `userId` from the JWT), never another user's.
5. The default page size (6) and maximum page size SHALL be configurable via application
   properties, consistent with the `catalog.search.page-size-*` convention.
6. The change SHALL preserve existing behavior for the other recipe endpoints
   (`POST /api/recipes`, `GET /api/recipes/{id}`, image stream, `DELETE`, `generate`).

---

## Requirement 5 — Swappable search backend (OpenSearch-ready seam)

**User story:** As the developer, I want saved-recipe search to be swappable to OpenSearch
later, so that if a user's collection ever grows large we can move it without a rewrite.

### Acceptance criteria

1. The system SHALL express saved-recipe search (query + pagination, scoped to a user)
   behind a service abstraction, so the retrieval/filter/sort/paginate logic is not baked
   into the controller.
2. The system SHALL provide an in-app implementation (DynamoDB query for the user's items
   + in-memory filter, sort, and paginate) as the default and only active implementation.
3. WHEN a future OpenSearch-backed implementation is added THEN it SHALL satisfy the same
   abstraction and the controller and frontend SHALL require no changes to switch to it.
4. The feature SHALL NOT provision or require any always-on search infrastructure in its
   default configuration, and SHALL NOT modify the shared catalog index or the catalog
   search feature.

---

## Requirement 6 — Cost, scope, and isolation constraints

**User story:** As the owner, I want this feature to add near-zero cost and not disturb
existing features, so that it's safe to ship.

### Acceptance criteria

1. The default configuration SHALL add no new always-on infrastructure and SHALL run on
   the compute already used by the backend.
2. The feature SHALL NOT modify the AI generation flow, the catalog search feature, the
   OpenSearch catalog index, or the dietary-restriction endpoints.
3. The feature SHALL NOT change the `Recipe` DynamoDB table schema or the `userId-index`
   GSI (the in-app path sorts/filters/paginates the queried items in the service layer).
4. The Saved Recipes detail view, save, and delete behaviors SHALL remain unchanged.
