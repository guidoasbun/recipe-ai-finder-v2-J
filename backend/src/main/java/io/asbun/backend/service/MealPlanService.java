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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns meal-plan validation, ownership enforcement, entry mutations, and recipe resolution.
 *
 * <p>Ownership: every op takes the acting {@code userId}; a plan the user does not own is
 * reported as {@link ResourceNotFoundException} (non-disclosing — not-owned is
 * indistinguishable from not-existing), mirroring {@code RecipeService}.
 *
 * <p>Entry mutations are read-modify-write on the plan item (load → mutate {@code entries} →
 * save). Correct for a single owner on their own plan; optimistic locking is deferred to a
 * future shared-plans spec.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealPlanService {

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

        long existing = mealPlanRepository.findByOwner(userId).size();
        if (existing >= maxPerUser) {
            throw new IllegalArgumentException(
                    "Plan limit reached (" + maxPerUser + " plans per user).");
        }

        validateDateRange(request.getStartDate(), request.getEndDate());

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
    }

    public List<MealPlanDto> listPlans(String userId) {
        return mealPlanRepository.findByOwner(userId).stream()
                .sorted(Comparator.comparing(
                        MealPlan::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDto)
                .toList();
    }

    public MealPlanDto getPlan(String mealPlanId, String userId) {
        return toDto(loadOwned(mealPlanId, userId));
    }

    public MealPlanDto updatePlan(String mealPlanId, UpdateMealPlanRequest request, String userId) {
        requireWritableAccount(userId);
        MealPlan plan = loadOwned(mealPlanId, userId);
        validateDateRange(request.getStartDate(), request.getEndDate());

        plan.setName(request.getName());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setServings(request.getServings());
        touch(plan);

        mealPlanRepository.save(plan);
        return toDto(plan);
    }

    public void deletePlan(String mealPlanId, String userId) {
        // Ownership enforced (non-disclosing) before delete.
        loadOwned(mealPlanId, userId);
        mealPlanRepository.delete(mealPlanId);
    }

    // ── Entry mutations ─────────────────────────────────────────────────────────

    public MealPlanDto addEntry(String mealPlanId, AddEntryRequest request, String userId) {
        requireWritableAccount(userId);
        MealPlan plan = loadOwned(mealPlanId, userId);

        List<MealPlanEntry> entries = plan.getEntries() != null ? plan.getEntries() : new ArrayList<>();
        if (entries.size() >= maxEntriesPerPlan) {
            throw new IllegalArgumentException(
                    "Entry limit reached (" + maxEntriesPerPlan + " entries per plan).");
        }

        // Reject a dangling reference before persisting (recipe must exist and be accessible).
        validateReference(request.getSource(), request.getRecipeId(), userId);

        MealPlanEntry entry = MealPlanEntry.builder()
                .entryId(UUID.randomUUID().toString())
                .date(request.getDate())
                .slot(request.getSlot())
                .servings(request.getServings())
                .recipeRef(RecipeRef.builder()
                        .source(request.getSource())
                        .recipeId(request.getRecipeId())
                        .build())
                .build();

        entries.add(entry);
        plan.setEntries(entries);
        touch(plan);
        mealPlanRepository.save(plan);
        return toDto(plan);
    }

    public MealPlanDto updateEntry(String mealPlanId, String entryId,
                                   UpdateEntryRequest request, String userId) {
        requireWritableAccount(userId);
        MealPlan plan = loadOwned(mealPlanId, userId);

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
        touch(plan);
        mealPlanRepository.save(plan);
        return toDto(plan);
    }

    public MealPlanDto removeEntry(String mealPlanId, String entryId, String userId) {
        requireWritableAccount(userId);
        MealPlan plan = loadOwned(mealPlanId, userId);

        List<MealPlanEntry> entries = plan.getEntries() != null ? plan.getEntries() : new ArrayList<>();
        boolean removed = entries.removeIf(e -> entryId.equals(e.getEntryId()));
        if (!removed) {
            throw new ResourceNotFoundException("Entry not found: " + entryId);
        }
        plan.setEntries(entries);
        touch(plan);
        mealPlanRepository.save(plan);
        return toDto(plan);
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
        if (startDate != null && endDate != null && startDate.compareTo(endDate) > 0) {
            // ISO yyyy-MM-dd sorts lexicographically, so string compare is a valid date compare.
            throw new IllegalArgumentException("startDate must not be after endDate");
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
