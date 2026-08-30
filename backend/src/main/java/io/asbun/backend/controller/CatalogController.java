package io.asbun.backend.controller;

import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.enums.DietaryRestriction;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.CatalogSearchQuery;
import io.asbun.backend.search.CatalogSearchResults;
import io.asbun.backend.search.CatalogSearchService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Validated
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogSearchService catalogSearchService;
    private final UserRepository userRepository;

    @Value("${catalog.search.page-size-default:20}")
    private int defaultPageSize;

    @Value("${catalog.search.page-size-max:50}")
    private int maxPageSize;

    /**
     * Searches the shared recipe catalog.
     *
     * <p>Dietary filtering: by default (when {@code filtersApplied} is false/absent) the
     * user's saved restrictions are applied. When {@code filtersApplied=true}, the request's
     * {@code tags} are used verbatim as an explicit override for this search — including an
     * empty list, which means "search with no dietary filter". This lets the UI express
     * "I deselected everything" distinctly from "I didn't touch the filters".
     */
    @GetMapping("/search")
    public ResponseEntity<CatalogSearchResults> search(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "false") boolean filtersApplied,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer pageSize,
            Authentication authentication) {

        String userId = getUserId(authentication);

        List<String> effectiveTags = resolveDietaryTags(tags, filtersApplied, userId);

        int size = pageSize == null ? defaultPageSize : pageSize;
        size = Math.max(1, Math.min(size, maxPageSize));
        int safePage = Math.max(0, page);

        CatalogSearchQuery query = new CatalogSearchQuery(q, effectiveTags, safePage, size);
        return ResponseEntity.ok(catalogSearchService.search(query));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogRecipeDto> getById(
            @PathVariable @Pattern(regexp = "^[a-f0-9]{1,64}$") String id,
            Authentication authentication) {
        // authentication enforced by security config; presence validates the caller
        getUserId(authentication);
        CatalogRecipeDto dto = catalogSearchService.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog recipe not found: " + id));
        return ResponseEntity.ok(dto);
    }

    /**
     * Resolves the dietary tags to enforce.
     *
     * <p>When {@code filtersApplied} is true, the request's tags are the explicit override
     * (an empty list means "no filter"). Otherwise the user's saved restrictions apply.
     * Request tags are validated against the enum: any invalid value causes a 400 rather
     * than being silently dropped (silently dropping "PIZZA_ONLY" would broaden results by
     * disabling filtering the caller intended).
     */
    private List<String> resolveDietaryTags(List<String> requestedTags, boolean filtersApplied, String userId) {
        if (filtersApplied) {
            List<String> requested = requestedTags == null ? List.of() : requestedTags;
            List<String> invalid = requested.stream()
                    .filter(t -> !isValidRestriction(t))
                    .distinct()
                    .collect(Collectors.toList());
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("Invalid dietary restriction(s): " + invalid);
            }
            return requested.stream().distinct().collect(Collectors.toList());
        }
        return userRepository.findById(userId)
                .map(u -> u.getDietaryRestrictions() == null
                        ? new ArrayList<String>()
                        : new ArrayList<>(u.getDietaryRestrictions()))
                .orElseGet(ArrayList::new);
    }

    private boolean isValidRestriction(String value) {
        if (value == null) {
            return false;
        }
        return Arrays.stream(DietaryRestriction.values())
                .anyMatch(r -> r.name().equals(value));
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }
}
