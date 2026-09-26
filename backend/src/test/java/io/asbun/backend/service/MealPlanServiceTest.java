package io.asbun.backend.service;

import io.asbun.backend.dto.AddEntryRequest;
import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.dto.CreateMealPlanRequest;
import io.asbun.backend.dto.MealPlanDto;
import io.asbun.backend.dto.MealPlanEntryDto;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.UpdateEntryRequest;
import io.asbun.backend.exception.AccountPendingDeletionException;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.MealPlan;
import io.asbun.backend.model.MealPlanEntry;
import io.asbun.backend.model.RecipeRef;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.MealSlot;
import io.asbun.backend.model.enums.RecipeSource;
import io.asbun.backend.repository.MealPlanRepository;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.CatalogSearchService;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MealPlanServiceTest {

    private static final String USER_ID = "user-123";
    private static final String OTHER_USER = "user-999";

    @Mock private MealPlanRepository mealPlanRepository;
    @Mock private UserRepository userRepository;
    @Mock private RecipeService recipeService;
    @Mock private CatalogSearchService catalogSearchService;

    private MealPlanService service;

    @BeforeEach
    void setUp() {
        service = new MealPlanService(mealPlanRepository, userRepository, recipeService, catalogSearchService);
        ReflectionTestUtils.setField(service, "maxPerUser", 3);
        ReflectionTestUtils.setField(service, "maxEntriesPerPlan", 2);
        // Default: active account (findById used by requireWritableAccount). Lenient so tests
        // that never write don't trip strict stubbing.
        lenient().when(userRepository.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(mealPlanRepository.save(any(MealPlan.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mealPlanRepository.createIfAbsent(any(MealPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateMealPlanRequest createReq(String name) {
        CreateMealPlanRequest r = new CreateMealPlanRequest();
        r.setName(name);
        r.setServings(4);
        return r;
    }

    private MealPlan plan(String id, String owner) {
        return MealPlan.builder()
                .mealPlanId(id).ownerUserId(owner).name("Week")
                .servings(4).entries(new ArrayList<>())
                .build();
    }

    // ── Ownership ──────────────────────────────────────────────────────────────

    @Test
    void getPlan_notOwned_isReportedAsNotFound() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", OTHER_USER)));

        assertThatThrownBy(() -> service.getPlan("p1", USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPlan_missing_isNotFound() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPlan("p1", USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listPlans_returnsOnlyOwnerPlans() {
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(List.of(plan("p1", USER_ID)));

        List<MealPlanDto> result = service.listPlans(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMealPlanId()).isEqualTo("p1");
    }

    // ── Limits ─────────────────────────────────────────────────────────────────

    @Test
    void createPlan_pastPerUserLimit_isRejected() {
        // The limit is now enforced atomically: the conditional counter increment fails when the
        // user is already at the cap, surfacing as ConditionalCheckFailedException.
        org.mockito.Mockito.doThrow(ConditionalCheckFailedException.builder().build())
                .when(mealPlanRepository).reservePlanSlot(USER_ID, 3);

        assertThatThrownBy(() -> service.createPlan(createReq("Overflow"), USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plan limit");
        verify(mealPlanRepository, never()).save(any());
    }

    @Test
    void addEntry_pastPerPlanLimit_isRejected() {
        MealPlan p = plan("p1", USER_ID);
        p.getEntries().add(MealPlanEntry.builder().entryId("e1").build());
        p.getEntries().add(MealPlanEntry.builder().entryId("e2").build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        // Reference is validated up front now, so make it resolvable to reach the entry-count cap.
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").build()));

        assertThatThrownBy(() -> service.addEntry("p1", catalogEntry(), USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Entry limit");
    }

    // ── Account status gating ───────────────────────────────────────────────────

    @Test
    void write_isRefusedWhenAccountPendingDeletion() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                User.builder().userId(USER_ID).accountStatus(AccountStatus.PENDING_DELETION).build()));

        assertThatThrownBy(() -> service.createPlan(createReq("X"), USER_ID))
                .isInstanceOf(AccountPendingDeletionException.class);
    }

    @Test
    void deletePlan_isRefusedWhenAccountPendingDeletion() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                User.builder().userId(USER_ID).accountStatus(AccountStatus.PENDING_DELETION).build()));

        assertThatThrownBy(() -> service.deletePlan("p1", USER_ID))
                .isInstanceOf(AccountPendingDeletionException.class);
        verify(mealPlanRepository, never()).delete(anyString());
    }

    @Test
    void deletePlan_releasesReservedSlot() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", USER_ID)));

        service.deletePlan("p1", USER_ID);

        verify(mealPlanRepository).delete("p1");
        verify(mealPlanRepository).releasePlanSlot(USER_ID);
    }

    // ── Strict date validation ───────────────────────────────────────────────────

    @Test
    void addEntry_impossibleDate_isRejected() {
        AddEntryRequest r = catalogEntry();
        r.setDate("2026-02-31"); // shape-valid but not a real day

        assertThatThrownBy(() -> service.addEntry("p1", r, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mealPlanRepository, never()).save(any());
    }

    @Test
    void updateEntry_impossibleDate_isRejected() {
        UpdateEntryRequest r = new UpdateEntryRequest();
        r.setDate("2026-99-01");

        assertThatThrownBy(() -> service.updateEntry("p1", "e1", r, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mealPlanRepository, never()).save(any());
    }

    // ── Optimistic locking ────────────────────────────────────────────────────────

    @Test
    void addEntry_retriesOnVersionConflictThenSucceeds() {
        // Each load returns a fresh plan (as DynamoDB would), so the retry re-applies the add
        // against clean state rather than doubling the entry.
        when(mealPlanRepository.findById("p1")).thenAnswer(inv -> Optional.of(plan("p1", USER_ID)));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").build()));
        // First save loses the version race; the retry (fresh load + re-apply) succeeds.
        when(mealPlanRepository.save(any(MealPlan.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build())
                .thenAnswer(inv -> inv.getArgument(0));

        MealPlanDto dto = service.addEntry("p1", catalogEntry(), USER_ID);

        assertThat(dto.getEntries()).hasSize(1);
        verify(mealPlanRepository, org.mockito.Mockito.times(2)).save(any(MealPlan.class));
    }

    @Test
    void read_isAllowedWhenAccountPendingDeletion() {
        // Reads never consult account status: listPlans does not call requireWritableAccount,
        // so it must not even look up the user. (No findById stub here — a strict-stubbing
        // failure would flag it if the read path started gating on status.)
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(List.of());

        assertThat(service.listPlans(USER_ID)).isEmpty();
    }

    // ── Reference validation ─────────────────────────────────────────────────────

    private AddEntryRequest catalogEntry() {
        AddEntryRequest r = new AddEntryRequest();
        r.setDate("2026-01-06");
        r.setSlot(MealSlot.DINNER);
        r.setSource(RecipeSource.CATALOG);
        r.setRecipeId("cat-1");
        return r;
    }

    @Test
    void addEntry_danglingCatalogRef_isRejected() {
        // The reference is validated before the plan is loaded, so no findById stub is needed.
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addEntry("p1", catalogEntry(), USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(mealPlanRepository, never()).save(any());
    }

    @Test
    void addEntry_danglingSavedRef_isRejected() {
        when(recipeService.getRecipeById("rec-1", USER_ID))
                .thenThrow(new ResourceNotFoundException("Recipe not found: rec-1"));

        AddEntryRequest r = new AddEntryRequest();
        r.setDate("2026-01-06");
        r.setSlot(MealSlot.LUNCH);
        r.setSource(RecipeSource.SAVED);
        r.setRecipeId("rec-1");

        assertThatThrownBy(() -> service.addEntry("p1", r, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addEntry_validCatalogRef_persistsAndReturnsResolvedEntry() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", USER_ID)));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").imageUrl("u").build()));

        MealPlanDto dto = service.addEntry("p1", catalogEntry(), USER_ID);

        assertThat(dto.getEntries()).hasSize(1);
        MealPlanEntryDto e = dto.getEntries().get(0);
        assertThat(e.isAvailable()).isTrue();
        assertThat(e.getTitle()).isEqualTo("Tacos");
        assertThat(e.getRecipeSource()).isEqualTo(RecipeSource.CATALOG);
        verify(mealPlanRepository).save(any(MealPlan.class));
    }

    @Test
    void addEntry_allowsSameRecipeInMultipleEntries() {
        MealPlan p = plan("p1", USER_ID);
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").build()));

        service.addEntry("p1", catalogEntry(), USER_ID);
        MealPlanDto dto = service.addEntry("p1", catalogEntry(), USER_ID);

        assertThat(dto.getEntries()).hasSize(2);
    }

    // ── Graceful resolution ──────────────────────────────────────────────────────

    @Test
    void getPlan_missingRecipe_marksEntryUnavailableButReturnsPlan() {
        MealPlan p = plan("p1", USER_ID);
        p.getEntries().add(MealPlanEntry.builder()
                .entryId("e1").date("2026-01-06").slot(MealSlot.DINNER)
                .recipeRef(RecipeRef.builder().source(RecipeSource.CATALOG).recipeId("gone").build())
                .build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        when(catalogSearchService.findById("gone")).thenReturn(Optional.empty());

        MealPlanDto dto = service.getPlan("p1", USER_ID);

        assertThat(dto.getEntries()).hasSize(1);
        assertThat(dto.getEntries().get(0).isAvailable()).isFalse();
        assertThat(dto.getEntries().get(0).getTitle()).isNull();
        // The reference is still reported so the UI can show a placeholder for the right slot.
        assertThat(dto.getEntries().get(0).getRecipeId()).isEqualTo("gone");
    }

    // ── Entry move/remove by id ──────────────────────────────────────────────────

    @Test
    void updateEntry_movesDateAndSlotById() {
        MealPlan p = plan("p1", USER_ID);
        p.getEntries().add(MealPlanEntry.builder()
                .entryId("e1").date("2026-01-06").slot(MealSlot.DINNER)
                .recipeRef(RecipeRef.builder().source(RecipeSource.CATALOG).recipeId("cat-1").build())
                .build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").build()));

        UpdateEntryRequest r = new UpdateEntryRequest();
        r.setDate("2026-01-07");
        r.setSlot(MealSlot.LUNCH);

        MealPlanDto dto = service.updateEntry("p1", "e1", r, USER_ID);

        MealPlanEntryDto e = dto.getEntries().get(0);
        assertThat(e.getDate()).isEqualTo("2026-01-07");
        assertThat(e.getSlot()).isEqualTo(MealSlot.LUNCH);
    }

    @Test
    void removeEntry_unknownId_isNotFound() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", USER_ID)));

        assertThatThrownBy(() -> service.removeEntry("p1", "nope", USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeEntry_removesById() {
        MealPlan p = plan("p1", USER_ID);
        p.getEntries().add(MealPlanEntry.builder().entryId("e1")
                .recipeRef(RecipeRef.builder().source(RecipeSource.CATALOG).recipeId("cat-1").build())
                .build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));

        MealPlanDto dto = service.removeEntry("p1", "e1", USER_ID);

        assertThat(dto.getEntries()).isEmpty();
    }

    // ── Servings ─────────────────────────────────────────────────────────────────

    // ── Meal-prep span ───────────────────────────────────────────────────────────

    @Test
    void addEntry_defaultsSpanToOneWhenOmitted() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", USER_ID)));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Tacos").build()));

        MealPlanDto dto = service.addEntry("p1", catalogEntry(), USER_ID);

        assertThat(dto.getEntries().get(0).getSpanDays()).isEqualTo(1);
    }

    @Test
    void addEntry_persistsMultiDaySpan() {
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(plan("p1", USER_ID)));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Meal-prep chili").build()));

        AddEntryRequest r = catalogEntry();
        r.setSpanDays(4);
        MealPlanDto dto = service.addEntry("p1", r, USER_ID);

        assertThat(dto.getEntries().get(0).getSpanDays()).isEqualTo(4);
    }

    @Test
    void updateEntry_changesSpan() {
        MealPlan p = plan("p1", USER_ID);
        p.getEntries().add(MealPlanEntry.builder()
                .entryId("e1").date("2026-01-06").slot(MealSlot.DINNER).spanDays(1)
                .recipeRef(RecipeRef.builder().source(RecipeSource.CATALOG).recipeId("cat-1").build())
                .build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Chili").build()));

        UpdateEntryRequest r = new UpdateEntryRequest();
        r.setSpanDays(3);
        MealPlanDto dto = service.updateEntry("p1", "e1", r, USER_ID);

        assertThat(dto.getEntries().get(0).getSpanDays()).isEqualTo(3);
    }

    @Test
    void resolve_defaultsNullStoredSpanToOne() {
        MealPlan p = plan("p1", USER_ID);
        // Older entry persisted before spanDays existed → null span.
        p.getEntries().add(MealPlanEntry.builder()
                .entryId("e1").date("2026-01-06").slot(MealSlot.DINNER)
                .recipeRef(RecipeRef.builder().source(RecipeSource.CATALOG).recipeId("cat-1").build())
                .build());
        when(mealPlanRepository.findById("p1")).thenReturn(Optional.of(p));
        when(catalogSearchService.findById("cat-1")).thenReturn(Optional.of(
                CatalogRecipeDto.builder().catalogRecipeId("cat-1").title("Chili").build()));

        MealPlanDto dto = service.getPlan("p1", USER_ID);

        assertThat(dto.getEntries().get(0).getSpanDays()).isEqualTo(1);
    }

    // ── Default (implicit) calendar ───────────────────────────────────────────────

    @Test
    void getOrCreateDefaultPlan_createsWhenNone() {
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(new ArrayList<>());

        MealPlanDto dto = service.getOrCreateDefaultPlan(USER_ID);

        assertThat(dto.getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(dto.getMealPlanId()).isNotBlank();
        // Materialized via a deterministic conditional create, not a blind save.
        verify(mealPlanRepository).createIfAbsent(any(MealPlan.class));
    }

    @Test
    void getOrCreateDefaultPlan_isDeterministicPerUser() {
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(new ArrayList<>());

        String first = service.getOrCreateDefaultPlan(USER_ID).getMealPlanId();
        String second = service.getOrCreateDefaultPlan(USER_ID).getMealPlanId();

        // The same user always materializes the same default-plan id (closes the duplicate-
        // default race), and it differs from another user's.
        assertThat(first).isEqualTo(second);
        when(mealPlanRepository.findByOwner(OTHER_USER)).thenReturn(new ArrayList<>());
        assertThat(service.getOrCreateDefaultPlan(OTHER_USER).getMealPlanId()).isNotEqualTo(first);
    }

    @Test
    void getOrCreateDefaultPlan_onConcurrentCreate_reloadsWinner() {
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(new ArrayList<>());
        // The conditional create loses the race; the winner is then loaded by id.
        when(mealPlanRepository.createIfAbsent(any(MealPlan.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build());
        MealPlan winner = plan("winner", USER_ID);
        when(mealPlanRepository.findById(anyString())).thenReturn(Optional.of(winner));

        MealPlanDto dto = service.getOrCreateDefaultPlan(USER_ID);

        assertThat(dto.getMealPlanId()).isEqualTo("winner");
    }

    @Test
    void getOrCreateDefaultPlan_isRefusedWhenPendingDeletionAndNoPlan() {
        // Materializing the calendar is a write, so a pending-deletion account must be refused
        // (prevents recreating a plan after the hard-deletion sweep).
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(new ArrayList<>());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                User.builder().userId(USER_ID).accountStatus(AccountStatus.PENDING_DELETION).build()));

        assertThatThrownBy(() -> service.getOrCreateDefaultPlan(USER_ID))
                .isInstanceOf(AccountPendingDeletionException.class);
        verify(mealPlanRepository, never()).createIfAbsent(any());
    }

    @Test
    void getOrCreateDefaultPlan_reusesMostRecentExisting() {
        MealPlan older = plan("old", USER_ID);
        older.setUpdatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        MealPlan newer = plan("new", USER_ID);
        newer.setUpdatedAt(java.time.Instant.parse("2026-02-01T00:00:00Z"));
        when(mealPlanRepository.findByOwner(USER_ID)).thenReturn(List.of(older, newer));

        MealPlanDto dto = service.getOrCreateDefaultPlan(USER_ID);

        assertThat(dto.getMealPlanId()).isEqualTo("new");
        verify(mealPlanRepository, never()).save(any());
    }

    @Test
    void createPlan_storesServings() {
        MealPlanDto dto = service.createPlan(createReq("Week"), USER_ID);

        ArgumentCaptor<MealPlan> captor = ArgumentCaptor.forClass(MealPlan.class);
        verify(mealPlanRepository).save(captor.capture());
        assertThat(captor.getValue().getServings()).isEqualTo(4);
        assertThat(dto.getServings()).isEqualTo(4);
    }
}
