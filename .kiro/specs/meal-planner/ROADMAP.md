# Meal Planner — Roadmap

## Overview

A meal planner for the recipe app: users schedule recipes onto a calendar, pull recipes
from the existing library (per-user saved `Recipe` + shared `CatalogRecipe`), and
progressively get grocery lists, richer recipe data, an AI planning assistant, and
calendar export.

This is a **large feature delivered as several smaller specs**. Each spec is
independently reviewable, ships something usable, and can be reprioritized or dropped
between milestones. Specs are ordered by dependency: each builds on the ones before it.

## Guiding principle: design forward-compatibly, build just-in-time

- **Design forward-compatibly** — shape data models so later specs slot in without
  breaking changes. Costs ~nothing, always worth it.
- **Build just-in-time** — only build the structures the *current* spec needs. On this
  stack, adding structure later is cheap (DynamoDB is schemaless; the self-hosted,
  basic-auth OpenSearch amends non-vector fields in place), so speculative scaffolding is
  net-negative: it risks building the wrong shape and paying to unbuild it.
- **Anticipate only expensive/breaking retrofits** — the one exception. Where a future
  change would be a breaking migration, reflect it in the *data shape* now (not in
  built-out features). Prime example: **plan ownership** — model it so single-user plans
  can grow to shared/household plans without a rewrite.

## Why the recipe-enrichment fields are NOT first

Adding `nutrition` / `servings` / `prepTime`-`cookTime` / `cuisine` feels foundational but
isn't. The smallest useful planner is "drop recipes on a calendar," which needs none of
them. These fields are additive and non-breaking, so deferring them is safe and lets each
land when a feature actually needs it. The genuinely missing piece — the thing that holds
everything back — is the `MealPlan` concept itself.

## What already exists and is reused (no rebuild)

- Per-user `Recipe` (DynamoDB, `userId-index` GSI) and shared read-only `CatalogRecipe`
  (with Titan embeddings) — the recipe library.
- `CatalogSearchService` (keyword / semantic / hybrid via Bedrock embeddings) — for
  finding recipes to add to a plan.
- Cognito/JWT auth with per-user scoping (userId from the `sub` claim) — the norm to
  mirror for new controllers.
- `User.dietaryRestrictions` — planner honors these automatically, like catalog search.
- `BedrockService` (LLM generation) — reused for AI plan generation / the assistant.
- Consent (`AI_DATA_PROCESSING`), quota/demo limits, Bucket4j rate limiting, SSE,
  audit/export scaffolding, and the Next.js `(protected)` route group + `apiFetch`/proxy
  pattern for new pages.

Note: OpenSearch here is self-hosted on an Oracle instance (basic auth). That means
`catalogRecipeId` is the document `_id`, so re-indexing is an idempotent **upsert** — new
fields amend existing docs in place, no index recreate, and **no re-embedding** (vectors
are read from DynamoDB). The only change that would force a rebuild is altering the
`knn_vector` mapping, which none of these specs do.

---

## Specs, in dependency order

### 1. Meal Plan Core  ← critical path, start here
The `MealPlan` entity + DynamoDB table + repository, `MealPlanController` CRUD, and a
basic calendar UI that adds recipes from the existing search. Everything else depends on
this. Ships as a working planner end-to-end.

- **Depends on:** nothing new (reuses recipe library, search, auth).
- **Design-forward notes:** model plan **ownership** so it can grow to shared/household
  without a breaking change; make a plan entry's recipe reference able to carry
  future per-entry data (e.g. servings) without reshaping.

### 2. Add-to-Plan Integration
"Add to plan" buttons on the existing browse and saved recipe cards, wiring the planner
into what's already there.

- **Depends on:** spec 1.
- Small, high-value.

### 3. Structured Ingredients
Quantity / unit / item on recipes, added **additively** alongside the existing
`List<String>` (nothing breaks). Includes the Bedrock generation prompt change to emit
structured ingredients, and the backfill approach for existing recipes.

- **Depends on:** nothing (independent), but is the **prerequisite for spec 4**.
- Only pursue when grocery lists are on the near horizon.

### 4. Grocery List
Aggregate ingredients across a plan's date range into a shopping list (unit
normalization, item merging).

- **Depends on:** spec 3 (structured ingredients) and spec 1.
- **Possible extension:** pantry tracking (subtract staples the user already has).

### 5. Recipe Enrichment (cuisine / servings / prep-cook time)
Add these fields when a feature needs them. `cuisine` is nearly free (derive from
`CatalogRecipe.sourceCountry`); `servings`/times are partial/source-dependent for the
2.2M catalog. Additive; no re-embed. Backfill on DynamoDB then re-project into OpenSearch
(in-place upsert on this setup).

- **Depends on:** nothing; enables cuisine filters, servings-scaling (with spec 3),
  prep-time balancing.
- Low priority; add on demand.

### 6. Nutrition
The expensive, derived one. No source data in RecipeNLG/TheMealDB; requires structured
quantities + a nutrition source or per-recipe LLM calls across 2.2M rows. Its own spec
precisely because it's a project.

- **Depends on:** spec 3 (for quantity-based derivation).
- Lowest priority; only if nutrition tracking is an actual goal.

### 7. AI Meal Planning Assistant (chatbot)
Conversational planning: "plan my week," then iterate ("make Tuesday vegetarian," "swap
the salmon," "add a dessert Friday"). Uses Bedrock **tool/function calling** to invoke
catalog search + meal-plan CRUD, grounded in the existing recipe library so it recommends
real recipes. **Subsumes one-shot AI plan generation** as one of the tools it can call.

- **Depends on:** spec 1 (a plan to manipulate); stronger with 2/4 (more to orchestrate).
- **Must respect:** existing `AI_DATA_PROCESSING` consent, per-user quota/demo limits,
  and rate limiting. Adds multi-turn conversation state per user.
- The most compelling feature; also the most moving parts (tool calling, grounding,
  conversation state, cost).

### 8. Calendar Export (iCal / Google)
One-way export of a plan to an iCal (.ics) feed and/or Google Calendar, so meals appear
alongside a user's real calendar.

- **Depends on:** spec 1.
- **Design note:** prefer iCal feed first (no second OAuth flow); Google Calendar API is
  a heavier integration (separate consent/token handling) layered on top if wanted.
  Deliberately kept as export, not the planner's source of truth.

---

## Nice-to-have features (attached to the relevant spec, pulled in on demand)

- **Favorites / quick-add** — star recipes so the planner surfaces them first. Tiny;
  candidate for an early small spec.
- **"Made this" / ratings** — mark what was cooked and liked; feeds better suggestions.
- **Plan templates / duplicate a week** — "copy last week," save a plan as reusable.
  Cheap once spec 1 exists.
- **"Cook from what I have"** — scope the existing generate-from-ingredients flow to
  planning.
- **Servings-aware scaling** — rides with spec 3.
- **Prep-time balancing** — rides with spec 5.

## Deliberately deferred / probably out of scope

- **Budget awareness** — needs price data we don't have.
- **Household / shared plans as a built feature** — NOT built early, but **anticipated in
  the spec 1 data model** (ownership shape) because retrofitting it is a breaking change.

---

## First milestone

Specs **1 (Meal Plan Core)** and **2 (Add-to-Plan Integration)** together are the first
meaningful milestone: a planner users can actually use. Everything after is enhancement.

We write specs **one at a time**, starting with Meal Plan Core (requirements → design →
tasks), since the others reference its data model.
