package io.asbun.backend.service;

import io.asbun.backend.metrics.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSseService {

    private static final String STREAM = "image";

    private final MetricsService metricsService;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String recipeId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onCompletion(() -> {
            emitters.remove(recipeId);
            metricsService.gauge("SseActiveEmitters", emitters.size(), "Stream", STREAM);
        });
        emitter.onTimeout(() -> {
            emitters.remove(recipeId);
            metricsService.gauge("SseActiveEmitters", emitters.size(), "Stream", STREAM);
        });
        emitter.onError(e -> {
            emitters.remove(recipeId);
            metricsService.gauge("SseActiveEmitters", emitters.size(), "Stream", STREAM);
        });
        emitters.put(recipeId, emitter);
        metricsService.gauge("SseActiveEmitters", emitters.size(), "Stream", STREAM);
        return emitter;
    }

    public void notifyImageReady(String recipeId) {
        SseEmitter emitter = emitters.remove(recipeId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("image-ready").data(recipeId));
            emitter.complete();
        } catch (IOException e) {
            log.debug("SSE client disconnected for recipe {}", recipeId);
            metricsService.count("SseBroadcastFailure", 1.0, "Stream", STREAM);
        }
    }
}
