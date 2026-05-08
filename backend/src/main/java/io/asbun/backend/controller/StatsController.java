package io.asbun.backend.controller;

import io.asbun.backend.dto.ModelStatsDto;
import io.asbun.backend.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/models")
    public ResponseEntity<ModelStatsDto> getModelStats() {
        return ResponseEntity.ok(statsService.getStats());
    }
}
