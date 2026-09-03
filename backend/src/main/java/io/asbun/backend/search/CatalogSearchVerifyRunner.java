package io.asbun.backend.search;

import io.asbun.backend.dto.CatalogRecipeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * One-off manual parity verification against the active {@link CatalogSearchService} (used with
 * the OpenSearch backend after a reindex). Runs a handful of representative searches and logs
 * the results, then the app continues running. Gated by {@code catalog.verify.enabled=true} so
 * it never runs on normal boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.verify.enabled", havingValue = "true")
public class CatalogSearchVerifyRunner implements CommandLineRunner {

    private final CatalogSearchService searchService;

    @Override
    public void run(String... args) {
        log.info("=== Catalog search verification START ===");

        // 1. Browse (blank query) — should return a page and a total.
        CatalogSearchResults browse = searchService.search(
                new CatalogSearchQuery("", List.of(), 0, 5));
        log.info("[browse] total={} page0Size={} firstTitle={}",
                browse.totalMatches(), browse.items().size(),
                browse.items().isEmpty() ? "-" : browse.items().get(0).getTitle());

        // 2. Keyword search.
        CatalogSearchResults chicken = searchService.search(
                new CatalogSearchQuery("chicken", List.of(), 0, 5));
        log.info("[keyword 'chicken'] total={} titles={}",
                chicken.totalMatches(), titles(chicken));

        // 3. Semantic / hybrid natural-language search.
        CatalogSearchResults nl = searchService.search(
                new CatalogSearchQuery("something warm and comforting for a cold night", List.of(), 0, 5));
        log.info("[semantic] total={} titles={}", nl.totalMatches(), titles(nl));

        // 4. Dietary filter.
        CatalogSearchResults vegan = searchService.search(
                new CatalogSearchQuery("", List.of("VEGAN"), 0, 5));
        log.info("[dietary VEGAN] total={} allTaggedVegan={}",
                vegan.totalMatches(), vegan.items().stream().allMatch(
                        r -> r.getDietaryTags() != null && r.getDietaryTags().contains("VEGAN")));

        // 5. Pagination determinism — page 0 vs page 1 should not overlap.
        CatalogSearchResults p0 = searchService.search(new CatalogSearchQuery("", List.of(), 0, 5));
        CatalogSearchResults p1 = searchService.search(new CatalogSearchQuery("", List.of(), 1, 5));
        boolean overlap = p0.items().stream().map(CatalogRecipeDto::getCatalogRecipeId)
                .anyMatch(id -> p1.items().stream().map(CatalogRecipeDto::getCatalogRecipeId)
                        .anyMatch(id2 -> id2.equals(id)));
        log.info("[pagination] p0={} p1={} overlap={}",
                ids(p0), ids(p1), overlap);

        // 6. findById round-trip.
        if (!browse.items().isEmpty()) {
            String id = browse.items().get(0).getCatalogRecipeId();
            Optional<CatalogRecipeDto> byId = searchService.findById(id);
            log.info("[findById {}] found={} title={}", id, byId.isPresent(),
                    byId.map(CatalogRecipeDto::getTitle).orElse("-"));
            Optional<CatalogRecipeDto> missing = searchService.findById("does-not-exist-000");
            log.info("[findById missing] empty={}", missing.isEmpty());
        }

        log.info("=== Catalog search verification END ===");
    }

    private String titles(CatalogSearchResults r) {
        return r.items().stream().map(CatalogRecipeDto::getTitle).limit(5).toList().toString();
    }

    private String ids(CatalogSearchResults r) {
        return r.items().stream().map(CatalogRecipeDto::getCatalogRecipeId).toList().toString();
    }
}
