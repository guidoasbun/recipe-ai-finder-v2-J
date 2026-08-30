# Requirements — Look for Existing Recipes

## Overview

Add a second way to discover recipes alongside the existing AI generation feature: a
searchable catalog of pre-made ("ready-made") recipes sourced from an open dataset.
Users get a new **"Look for Existing Recipes"** tab where they can search the catalog
by keyword and (optionally) by meaning, with results filtered by the same dietary
restrictions already stored on their account.

The AI recipe generation feature is unchanged.

### Scope decisions (already agreed)

- **Search runs in-app** (inside the existing Spring Boot backend), not on OpenSearch,
  to keep cost at ~$0 for current traffic (2 users, a handful of actions per day).
- All search logic sits behind a **`CatalogSearchService` interface** so an OpenSearch
  implementation can be swapped in later via configuration, without a rewrite.
- **Semantic search uses Amazon Bedrock Titan Text Embeddings V2.** Recipe embeddings
  are computed once at ingestion; query embeddings are computed per search. Cost is
  ~$1 one-time plus pennies/month.
- Dietary filtering **reuses** the existing `DietaryRestriction` enum and the user's
  stored `dietaryRestrictions`.
- The catalog is **shared and read-only** for users (not per-user like saved recipes).
- This is a **non-commercial** app, which allows use of research-oriented datasets;
  licensing/attribution must still be respected.
- **Dataset is phased:** start with ~300 recipes (TheMealDB) to prove the pipeline, then
  grow to a **10K–50K subset of RecipeNLG** as the in-app steady state.
- The **in-app backend is capped at ~50K recipes** (a JVM-memory ceiling). The full
  ~2.2M RecipeNLG dataset is out of scope for in-app and is the trigger for an OpenSearch
  backend, not something the in-app path must support.

---

## Requirement 1 — Browse/search catalog tab

**User story:** As a signed-in user, I want a dedicated "Look for Existing Recipes" tab,
so that I can search a catalog of ready-made recipes separately from AI generation.

### Acceptance criteria

1. WHEN a signed-in user views the app navigation THEN the system SHALL display a
   "Look for Existing Recipes" tab alongside the existing tabs.
2. WHEN a user opens the tab THEN the system SHALL display a search input and a results
   area.
3. WHEN the tab first loads and no query has been entered THEN the system SHALL display
   either a default/browse listing or an empty prompt state (no error).
4. IF the user is not authenticated THEN the system SHALL apply the same access control
   as the other protected pages (redirect/deny per existing behavior).

---

## Requirement 2 — Keyword search

**User story:** As a user, I want to search catalog recipes by keyword, so that I can find
recipes matching ingredients, titles, or terms I care about.

### Acceptance criteria

1. WHEN a user submits a non-empty query THEN the system SHALL return catalog recipes
   whose title, description, or ingredients match the query terms.
2. WHEN multiple recipes match THEN the system SHALL return them ordered by relevance
   (better/closer matches first).
3. WHEN no recipes match THEN the system SHALL return an empty result set and the UI
   SHALL show a "no results" state (not an error).
4. WHEN a query is submitted THEN the system SHALL return results paginated (a bounded
   page size) with a way to request the next page.
5. WHEN a query contains only whitespace or is empty THEN the system SHALL treat it as a
   browse/no-query request rather than an error.
6. WHEN a user submits a query THEN the system SHALL validate and bound the input length
   consistent with existing request-validation conventions.

---

## Requirement 3 — Semantic (meaning-based) search via Bedrock embeddings

**User story:** As a user, I want to search by describing what I want in natural language,
so that I can find relevant recipes even when I don't use the exact keywords.

### Acceptance criteria

1. WHEN semantic search is enabled AND a user submits a query THEN the system SHALL
   compute a query embedding using Amazon Bedrock Titan Text Embeddings V2 and rank
   catalog recipes by vector similarity to that embedding.
2. WHEN the catalog is ingested THEN the system SHALL compute and persist an embedding
   vector for each catalog recipe so query-time work is limited to embedding the query
   and comparing vectors.
3. IF the Bedrock embedding call fails THEN the system SHALL fall back to keyword search
   and still return results (degrade gracefully, no user-facing hard failure).
4. WHEN semantic search is disabled by configuration THEN the system SHALL use keyword
   search only and SHALL NOT call Bedrock at query time.
5. The system SHALL allow keyword and semantic ranking to be combined or selected via
   configuration, without changing the public API contract.
6. The system SHALL produce bulk ingestion embeddings via a strategy abstraction. The
   synchronous strategy (built now) SHALL pace requests under the RPM quota, back off on
   throttling, and be resumable (skip already-embedded recipes); it SHALL serve catalogs
   up to ~50K recipes.
7. WHEN the full ~2.2M dataset is targeted THEN the system SHALL use a Bedrock Batch
   Inference strategy (S3 JSONL, async) instead of the synchronous loop. This strategy is
   scaffolded for the future and is NOT built in the initial implementation.
8. Both embedding strategies SHALL produce identical 1,024-dim vectors stored identically,
   so switching strategies requires no change to search or storage.

---

## Requirement 4 — Dietary restriction filtering (reuse existing)

**User story:** As a user with dietary restrictions, I want catalog results to respect the
same restrictions I already set for AI generation, so that I only see recipes I can eat.

### Acceptance criteria

1. WHEN a user searches the catalog THEN the system SHALL, by default, apply the dietary
   restrictions stored on that user's account (`user.dietaryRestrictions`).
2. WHEN filtering by dietary restrictions THEN the system SHALL only return catalog
   recipes whose `dietaryTags` satisfy every applied restriction.
3. WHEN a user adjusts the dietary filters in the UI for a given search THEN the system
   SHALL apply the adjusted set for that search without modifying the user's saved
   account restrictions.
4. The system SHALL reuse the existing `DietaryRestriction` enum values and the frontend
   `lib/dietary.ts` option list; it SHALL NOT introduce a parallel/duplicate restriction
   vocabulary.
5. WHEN a catalog recipe is ingested THEN the system SHALL assign it `dietaryTags` drawn
   from the `DietaryRestriction` enum so filtering is a tag match, not a runtime analysis.

---

## Requirement 5 — Recipe detail view

**User story:** As a user, I want to open a catalog recipe and see its full details, so that
I can actually cook it.

### Acceptance criteria

1. WHEN a user selects a catalog recipe from results THEN the system SHALL display its
   title, description, ingredient list, and steps.
2. WHEN a catalog recipe has source/attribution metadata THEN the system SHALL display
   the attribution as required by the dataset's license.
3. IF a requested catalog recipe does not exist THEN the system SHALL return a not-found
   response and the UI SHALL show a not-found state.

---

## Requirement 6 — Catalog ingestion pipeline

**User story:** As the developer/operator, I want a repeatable way to load an open recipe
dataset into the catalog, so that the searchable database can be built and rebuilt.

### Acceptance criteria

1. The system SHALL provide an ingestion process that reads an open recipe dataset,
   normalizes each recipe into the catalog schema, assigns `dietaryTags`, computes a
   Bedrock embedding, and writes it to the catalog store.
2. The ingestion process SHALL be runnable independently of normal request handling
   (e.g. a standalone/one-off task), and SHALL NOT run automatically on every app start.
3. The ingestion process SHALL support multiple dataset sources behind a common parser
   abstraction, starting with TheMealDB (~300, Phase 1) and a RecipeNLG subset
   (10K–50K, Phase 2), without a pipeline rewrite to add a source.
4. WHEN targeting the in-app backend THEN ingestion SHALL be limited to a bounded subset
   (≤ ~50K recipes); the full ~2.2M dataset SHALL NOT be ingested for the in-app backend.
5. WHEN ingestion assigns dietary tags THEN it SHALL use a deterministic, documented
   rule set (ingredient keyword matching) as the baseline, with the option to refine
   ambiguous cases later.
6. The ingestion process SHALL be idempotent per recipe (re-running does not create
   duplicates for the same source recipe).
7. The ingestion process SHALL record enough source metadata to satisfy dataset
   attribution/licensing requirements.

---

## Requirement 7 — Swappable search backend (OpenSearch-ready)

**User story:** As the developer, I want the search implementation to be swappable, so that
I can move to OpenSearch later if traffic grows, without rewriting the feature.

### Acceptance criteria

1. The system SHALL define a `CatalogSearchService` interface that expresses search
   (query + dietary filters + pagination) and single-recipe retrieval independent of the
   backing store.
2. The system SHALL provide an in-app implementation (in-memory / DynamoDB-backed) that
   satisfies the interface for current traffic.
3. The system SHALL select the active implementation via configuration (a property/flag),
   defaulting to the in-app implementation.
4. WHEN a future OpenSearch implementation is added THEN it SHALL satisfy the same
   interface and the API/controller/frontend SHALL require no changes to switch to it.
5. The embedding/tagging data produced at ingestion SHALL be reusable by a future
   OpenSearch implementation (e.g. vectors and tags are persisted, not implementation-locked).

---

## Requirement 8 — Cost and isolation constraints

**User story:** As the owner, I want this feature to add near-zero recurring cost and not
affect the existing AI feature, so that it's safe to ship.

### Acceptance criteria

1. The default configuration SHALL NOT provision or require any always-on search
   infrastructure (no OpenSearch cluster/collection).
2. The feature SHALL run on the compute already used by the backend.
3. The only new AWS usage in the default configuration SHALL be Bedrock embedding calls
   (one-time at ingestion, per-query at search time) and catalog storage.
4. The feature SHALL NOT modify the existing AI generation flow, the `Recipe`/saved-recipe
   tables, or the existing dietary-restriction endpoints' behavior.
