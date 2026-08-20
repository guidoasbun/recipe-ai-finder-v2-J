package io.asbun.backend.controller;

import io.asbun.backend.dto.ModelStatsDto;
import io.asbun.backend.service.StatsSseService;
import io.asbun.backend.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

@Validated
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final StatsSseService statsSseService;

    @GetMapping("/models")
    public ResponseEntity<ModelStatsDto> getModelStats() {
        return ResponseEntity.ok(statsService.getStats());
    }

    @GetMapping("/stream")
    public SseEmitter streamStats() {
        SseEmitter emitter = statsSseService.subscribe();
        Optional<ModelStatsDto> cached = statsService.getCachedStatsIfFresh();
        if (cached.isPresent()) {
            statsSseService.sendToEmitter(emitter, cached.get());
        } else {
            statsService.computeAndNotifyAsync();
        }
        return emitter;
    }
}
