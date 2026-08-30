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
     * Searches the shared recipe catalog. Dietary filtering defaults to the user's saved
     * restrictions; passing {@code tags} overrides them for this search only (does not
     * modify the user's account).
     */
    @GetMapping("/search")
    public ResponseEntity<CatalogSearchResults> search(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer pageSize,
            Authentication authentication) {

        String userId = getUserId(authentication);

        List<String> effectiveTags = resolveDietaryTags(tags, userId);

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
     * Uses request-supplied tags when present (validated against the enum), otherwise the
     * user's saved dietary restrictions.
     */
    private List<String> resolveDietaryTags(List<String> requestedTags, String userId) {
        if (requestedTags != null) {
            List<String> valid = requestedTags.stream()
                    .filter(this::isValidRestriction)
                    .distinct()
                    .collect(Collectors.toList());
            return valid;
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
