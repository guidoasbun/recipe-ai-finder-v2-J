package io.asbun.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ImageSseService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String recipeId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onCompletion(() -> emitters.remove(recipeId));
        emitter.onTimeout(() -> emitters.remove(recipeId));
        emitter.onError(e -> emitters.remove(recipeId));
        emitters.put(recipeId, emitter);
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
        }
    }
}
