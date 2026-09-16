package io.asbun.backend.service;

import io.asbun.backend.dto.AddEntryRequest;
import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.dto.CreateMealPlanRequest;
import io.asbun.backend.dto.MealPlanDto;
import io.asbun.backend.dto.MealPlanEntryDto;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.UpdateEntryRequest;
import io.asbun.backend.dto.UpdateMealPlanRequest;
import io.asbun.backend.exception.AccountPendingDeletionException;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.MealPlan;
import io.asbun.backend.model.MealPlanEntry;
import io.asbun.backend.model.RecipeRef;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.RecipeSource;
import io.asbun.backend.repository.MealPlanRepository;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.CatalogSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns meal-plan validation, ownership enforcement, entry mutations, and recipe resolution.
 *
 * <p>Ownership: every op takes the acting {@code userId}; a plan the user does not own is
 * reported as {@link ResourceNotFoundException} (non-disclosing — not-owned is
 * indistinguishable from not-existing), mirroring {@code RecipeService}.
 *
 * <p>Entry mutations are read-modify-write on the plan item (load → mutate {@code entries} →
 * save), guarded by the plan's {@code @DynamoDbVersionAttribute}. Two writers that load the
 * same plan and each save their own entry list no longer clobber each other: the second put
 * fails its version condition, and {@link #mutate} reloads fresh state and re-applies the
 * change before retrying. This makes concurrent add/update/remove safe for the single owner
 * editing from multiple tabs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealPlanService {

    /** Bounds the optimistic-locking retry loop so a genuinely hot item can't spin forever. */
    private static final int MAX_WRITE_ATTEMPTS = 5;

    /**
     * Namespace for deriving each user's deterministic default-plan id (UUIDv5-style). A given
     * {@code userId} always maps to the same {@code mealPlanId}, so the first-load create is a
     * conditional put on a known key rather than a query-then-random-put race.
     */
    private static final UUID DEFAULT_PLAN_NAMESPACE =
            UUID.fromString("a7c3f0d2-6b1e-4e9a-8c2d-9f5b1a0e4c7d");

    /** Strict ISO date parsing: rejects impossible values like 2026-02-31 or 2026-99-01. */
    private static final DateTimeFormatter STRICT_ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final MealPlanRepository mealPlanRepository;
    private final UserRepository userRepository;
    private final RecipeService recipeService;
    private final CatalogSearchService catalogSearchService;

    @Value("${mealplans.max-per-user:100}")
    private int maxPerUser;

    @Value("${mealplans.max-entries-per-plan:500}")
    private int maxEntriesPerPlan;

    // ── Plan CRUD ─────────────────────────────────────────────────────────────

    public MealPlanDto createPlan(CreateMealPlanRequest request, String userId) {
        requireWritableAccount(userId);
        validateDateRange(request.getStartDate(), request.getEndDate());

        // Reserve a slot atomically: the conditional increment is the limit check, so parallel
        // creates cannot all slip past a stale count and exceed the cap.
        try {
            mealPlanRepository.reservePlanSlot(userId, maxPerUser);
        } catch (ConditionalCheckFailedException atCap) {
            throw new IllegalArgumentException(
                    "Plan limit reached (" + maxPerUser + " plans per user).");
        }

        // From here the slot is reserved; release it if the create can't be persisted so a
        // failure doesn't permanently consume one of the user's slots.
        try {
            Instant now = Instant.now();
            MealPlan plan = MealPlan.builder()
                    .mealPlanId(UUID.randomUUID().toString())
                    .ownerUserId(userId)
                    .name(request.getName())
                    .startDate(request.getStartDate())
                    .endDate(request.getEndDate())
                    .servings(request.getServings())
                    .entries(new ArrayList<>())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            mealPlanRepository.save(plan);
            return toDto(plan);
        } catch (RuntimeException e) {
            releaseSlotQuietly(userId);
            throw e;
        }
    }

    /**
     * Lists the user's plans as <em>summaries</em>: plan attributes + entry count, but no
     * resolved entries. Resolving every entry of every plan here would fan out to as many as
     * {@code maxPerUser × maxEntriesPerPlan} recipe lookups (and can trigger paid saved-recipe
     * image side effects) just to render a list. Callers open a plan via {@code getPlan} /
     * {@code getOrCreateDefaultPlan} to get fully resolved entries.
     */
    public List<MealPlanDto> listPlans(String userId) {
        return mealPlanRepository.findByOwner(userId).stream()
                .sorted(Comparator.comparing(
                        MealPlan::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toSummaryDto)
                .toList();
    }

    /** Summary view: no recipe resolution, just attributes and the entry count. */
    private MealPlanDto toSummaryDto(MealPlan plan) {
        int count = plan.getEntries() != null ? plan.getEntries().size() : 0;
        return MealPlanDto.builder()
                .mealPlanId(plan.getMealPlanId())
                .ownerUserId(plan.getOwnerUserId())
                .name(plan.getName())
                .startDate(plan.getStartDate())
                .endDate(plan.getEndDate())
                .servings(plan.getServings())
                .entries(null)
                .entryCount(count)
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }

    /**
     * Resolves the user's single implicit calendar (Option A: the UI exposes one calendar per
     * user rather than multiple named plans).
     *
     * <p>If the user already has plans (e.g. created under the old multi-plan UI), returns the
     * most-recently-updated one so their data folds into the calendar. Otherwise it
     * materializes the calendar at a <em>deterministic</em> per-user id via a conditional
     * "create if absent" put: two concurrent first-loads race on the same key, exactly one put
     * wins, and the loser reloads the winner instead of both creating a different "single"
     * calendar. This closes the query-then-random-put race against the eventually-consistent
     * owner GSI.
     *
     * <p>Materializing the calendar is a write, so it is gated by {@link #requireWritableAccount}
     * — a request must not recreate a plan after the account's hard-deletion sweep has run,
     * which would leave orphaned personal data. A pure read (the user already has a plan) is not
     * gated.
     */
    public MealPlanDto getOrCreateDefaultPlan(String userId) {
        Optional<MealPlan> existing = findDefaultCandidate(userId);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }

        // No plan yet — this branch writes, so enforce the lifecycle gate first.
        requireWritableAccount(userId);

        String planId = defaultPlanId(userId);
        Instant now = Instant.now();
        MealPlan plan = MealPlan.builder()
                .mealPlanId(planId)
                .ownerUserId(userId)
                .name("My Meal Plan")
                .entries(new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            mealPlanRepository.createIfAbsent(plan);
            return toDto(plan);
        } catch (ConditionalCheckFailedException raced) {
            // Another concurrent first-load created it. Load the winner and return that.
            MealPlan winner = mealPlanRepository.findById(planId)
                    .or(() -> findDefaultCandidate(userId))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Meal plan not found after concurrent create: " + planId));
            return toDto(winner);
        }
    }

    /** The most-recently-updated existing plan for the user, if any. */
    private Optional<MealPlan> findDefaultCandidate(String userId) {
        return mealPlanRepository.findByOwner(userId).stream()
                .max(Comparator.comparing(
                        MealPlan::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** Deterministic per-user default-plan id (UUIDv5 over the namespace + userId). */
    private String defaultPlanId(String userId) {
        byte[] name = (DEFAULT_PLAN_NAMESPACE + ":" + userId).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(name).toString();
    }

    public MealPlanDto getPlan(String mealPlanId, String userId) {
        return toDto(loadOwned(mealPlanId, userId));
    }

    public MealPlanDto updatePlan(String mealPlanId, UpdateMealPlanRequest request, String userId) {
        requireWritableAccount(userId);
        validateDateRange(request.getStartDate(), request.getEndDate());
        // Under optimistic locking too, so a concurrent entry mutation doesn't 500 this update.
        return mutate(mealPlanId, userId, plan -> {
            plan.setName(request.getName());
            plan.setStartDate(request.getStartDate());
            plan.setEndDate(request.getEndDate());
            plan.setServings(request.getServings());
        });
    }

    public void deletePlan(String mealPlanId, String userId) {
        // Deleting a plan is a write, so refuse it for accounts pending/failed deletion —
        // the same gate applied to create, update, and entry mutations.
        requireWritableAccount(userId);
        // Ownership enforced (non-disclosing) before delete.
        loadOwned(mealPlanId, userId);
        mealPlanRepository.delete(mealPlanId);
        // Free the reserved slot so the user can create another plan. Best-effort: the plan is
        // already gone, and the counter self-heals (clamped at zero) if this decrement is lost.
        releaseSlotQuietly(userId);
    }

    // ── Entry mutations ─────────────────────────────────────────────────────────

    public MealPlanDto addEntry(String mealPlanId, AddEntryRequest request, String userId) {
        requireWritableAccount(userId);
        // Strict date check: the DTO regex only validates shape, so reject impossible days
        // (2026-02-31, 2026-99-01) here before they're stored and mis-rendered by the client.
        parseStrictDate(request.getDate(), "date");
        // Validate the reference once, up front — it doesn't depend on plan state, so there's
        // no need to re-check it on every optimistic-locking retry.
        validateReference(request.getSource(), request.getRecipeId(), userId);

        return mutate(mealPlanId, userId, plan -> {
            List<MealPlanEntry> entries = plan.getEntries() != null
                    ? plan.getEntries() : new ArrayList<>();
            if (entries.size() >= maxEntriesPerPlan) {
                throw new IllegalArgumentException(
                        "Entry limit reached (" + maxEntriesPerPlan + " entries per plan).");
            }

            MealPlanEntry entry = MealPlanEntry.builder()
                    .entryId(UUID.randomUUID().toString())
                    .date(request.getDate())
                    .slot(request.getSlot())
                    .servings(request.getServings())
                    // Meal-prep span: default to a single day when omitted.
                    .spanDays(request.getSpanDays() == null ? 1 : request.getSpanDays())
                    .recipeRef(RecipeRef.builder()
                            .source(request.getSource())
                            .recipeId(request.getRecipeId())
                            .build())
                    .build();

            entries.add(entry);
            plan.setEntries(entries);
        });
    }

    public MealPlanDto updateEntry(String mealPlanId, String entryId,
                                   UpdateEntryRequest request, String userId) {
        requireWritableAccount(userId);
        // Strict date check on the incoming move target (null = unchanged), same as add.
        parseStrictDate(request.getDate(), "date");
        return mutate(mealPlanId, userId, plan -> {
            MealPlanEntry entry = findEntry(plan, entryId);
            if (request.getDate() != null) {
                entry.setDate(request.getDate());
            }
            if (request.getSlot() != null) {
                entry.setSlot(request.getSlot());
            }
            if (request.getServings() != null) {
                entry.setServings(request.getServings());
            }
            if (request.getSpanDays() != null) {
                entry.setSpanDays(request.getSpanDays());
            }
        });
    }

    public MealPlanDto removeEntry(String mealPlanId, String entryId, String userId) {
        requireWritableAccount(userId);
        return mutate(mealPlanId, userId, plan -> {
            List<MealPlanEntry> entries = plan.getEntries() != null
                    ? plan.getEntries() : new ArrayList<>();
            boolean removed = entries.removeIf(e -> entryId.equals(e.getEntryId()));
            if (!removed) {
                throw new ResourceNotFoundException("Entry not found: " + entryId);
            }
            plan.setEntries(entries);
        });
    }

    /**
     * Runs a read-modify-write on the owned plan under optimistic locking: load fresh, apply
     * {@code mutation}, {@code touch}, and save. If the version-guarded save loses to a
     * concurrent writer ({@link ConditionalCheckFailedException}), reload and re-apply the
     * mutation against the winner's state, up to {@link #MAX_WRITE_ATTEMPTS} times. Because the
     * mutation is re-run each attempt, a concurrent add/update/remove is preserved rather than
     * silently discarded by a last-writer-wins put.
     */
    private MealPlanDto mutate(String mealPlanId, String userId, Consumer<MealPlan> mutation) {
        ConditionalCheckFailedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            MealPlan plan = loadOwned(mealPlanId, userId);
            mutation.accept(plan);
            touch(plan);
            try {
                mealPlanRepository.save(plan);
                return toDto(plan);
            } catch (ConditionalCheckFailedException conflict) {
                lastConflict = conflict;
                log.debug("Optimistic-lock conflict on plan {} (attempt {}/{}), retrying",
                        mealPlanId, attempt, MAX_WRITE_ATTEMPTS);
            }
        }
        throw new IllegalStateException(
                "Meal plan is being modified concurrently; please retry.", lastConflict);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Loads a plan the user owns, or throws the non-disclosing not-found. */
    private MealPlan loadOwned(String mealPlanId, String userId) {
        MealPlan plan = mealPlanRepository.findById(mealPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));
        if (!userId.equals(plan.getOwnerUserId())) {
            // Not-owned is indistinguishable from not-existing.
            throw new ResourceNotFoundException("Meal plan not found: " + mealPlanId);
        }
        return plan;
    }

    private MealPlanEntry findEntry(MealPlan plan, String entryId) {
        List<MealPlanEntry> entries = plan.getEntries() != null ? plan.getEntries() : List.of();
        return entries.stream()
                .filter(e -> entryId.equals(e.getEntryId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Entry not found: " + entryId));
    }

    /**
     * Refuses writes when the account is pending/failed deletion, mirroring
     * {@code RecipeController.generate}. A missing user is treated as writable (auth already
     * proved the caller; user upsert happens elsewhere).
     */
    private void requireWritableAccount(String userId) {
        userRepository.findById(userId).ifPresent(u -> {
            AccountStatus status = u.getAccountStatus();
            if (status == AccountStatus.PENDING_DELETION || status == AccountStatus.DELETION_FAILED) {
                throw new AccountPendingDeletionException("Account is pending deletion");
            }
        });
    }

    /** Verifies the reference resolves and is accessible; throws (→ 404/400) if not. */
    private void validateReference(RecipeSource source, String recipeId, String userId) {
        if (source == RecipeSource.SAVED) {
            // Owner-checked; throws ResourceNotFoundException if missing/not owned.
            recipeService.getRecipeById(recipeId, userId);
        } else {
            catalogSearchService.findById(recipeId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Catalog recipe not found: " + recipeId));
        }
    }

    private void validateDateRange(String startDate, String endDate) {
        // Reject impossible dates (e.g. 2026-02-31) that pass the DTO's shape-only regex but
        // would be silently normalized to a different day by the client's Date constructor.
        LocalDate start = parseStrictDate(startDate, "startDate");
        LocalDate end = parseStrictDate(endDate, "endDate");
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
    }

    /**
     * Parses an ISO {@code yyyy-MM-dd} string with strict resolution so calendar-impossible
     * values (2026-02-31, 2026-99-01, 2026-13-01) are rejected as {@link IllegalArgumentException}
     * (→ 400) rather than persisted. Null is allowed (caller decides if the field is required).
     */
    LocalDate parseStrictDate(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, STRICT_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    field + " must be a valid ISO date (yyyy-MM-dd): " + value);
        }
    }

    /** Best-effort slot release; swallows the counter's conditional (nothing to release). */
    private void releaseSlotQuietly(String userId) {
        try {
            mealPlanRepository.releasePlanSlot(userId);
        } catch (ConditionalCheckFailedException ignored) {
            // Counter already at zero / absent — nothing to release.
        } catch (RuntimeException e) {
            log.warn("Failed to release plan slot for {}: {}", userId, e.getMessage());
        }
    }

    private void touch(MealPlan plan) {
        plan.setUpdatedAt(Instant.now());
    }

    // ── Mapping + resolution ────────────────────────────────────────────────────

    private MealPlanDto toDto(MealPlan plan) {
        List<MealPlanEntry> entries = plan.getEntries() != null ? plan.getEntries() : List.of();
        List<MealPlanEntryDto> entryDtos = resolveEntries(entries, plan.getOwnerUserId());
        return MealPlanDto.builder()
                .mealPlanId(plan.getMealPlanId())
                .ownerUserId(plan.getOwnerUserId())
                .name(plan.getName())
                .startDate(plan.getStartDate())
                .endDate(plan.getEndDate())
                .servings(plan.getServings())
                .entries(entryDtos)
                .entryCount(entryDtos.size())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }

    /**
     * Resolves each entry's recipe reference to a slim display view, batched by de-duplicating
     * repeated recipe ids (the same recipe can appear in many entries — leftovers). Resolution
     * is best-effort per entry: a missing/inaccessible recipe yields an "unavailable" entry
     * (available=false, null details) so the rest of the plan still renders (design §3.3).
     */
    private List<MealPlanEntryDto> resolveEntries(List<MealPlanEntry> entries, String ownerUserId) {
        // Cache resolutions by (source|recipeId) so a recipe referenced N times resolves once.
        Map<String, ResolvedRecipe> cache = new HashMap<>();
        List<MealPlanEntryDto> out = new ArrayList<>(entries.size());

        for (MealPlanEntry e : entries) {
            RecipeRef ref = e.getRecipeRef();
            ResolvedRecipe resolved = (ref == null)
                    ? ResolvedRecipe.unavailable()
                    : cache.computeIfAbsent(cacheKey(ref), k -> resolve(ref, ownerUserId));

            out.add(MealPlanEntryDto.builder()
                    .entryId(e.getEntryId())
                    .date(e.getDate())
                    .slot(e.getSlot())
                    .servings(e.getServings())
                    // Default older/null spans to 1 so the client always gets a concrete span.
                    .spanDays(e.getSpanDays() == null ? 1 : e.getSpanDays())
                    .recipeSource(ref != null ? ref.getSource() : null)
                    .recipeId(ref != null ? ref.getRecipeId() : null)
                    .available(resolved.available)
                    .title(resolved.title)
                    .imageUrl(resolved.imageUrl)
                    .build());
        }
        return out;
    }

    private String cacheKey(RecipeRef ref) {
        return ref.getSource() + "|" + ref.getRecipeId();
    }

    private ResolvedRecipe resolve(RecipeRef ref, String ownerUserId) {
        try {
            if (ref.getSource() == RecipeSource.SAVED) {
                RecipeDto dto = recipeService.getRecipeById(ref.getRecipeId(), ownerUserId);
                return new ResolvedRecipe(true, dto.getTitle(), dto.getImageUrl());
            }
            Optional<CatalogRecipeDto> dto = catalogSearchService.findById(ref.getRecipeId());
            return dto.map(d -> new ResolvedRecipe(true, d.getTitle(), d.getImageUrl()))
                    .orElseGet(ResolvedRecipe::unavailable);
        } catch (ResourceNotFoundException e) {
            // Recipe deleted or no longer accessible — degrade this entry, keep the plan.
            return ResolvedRecipe.unavailable();
        } catch (RuntimeException e) {
            log.warn("Failed to resolve recipe {} ({}): {}",
                    ref.getRecipeId(), ref.getSource(), e.getMessage());
            return ResolvedRecipe.unavailable();
        }
    }

    private record ResolvedRecipe(boolean available, String title, String imageUrl) {
        static ResolvedRecipe unavailable() {
            return new ResolvedRecipe(false, null, null);
        }
    }
}
