package io.asbun.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.dto.ModelStatsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class StatsSseService {

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter subscribe() {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));
        emitter.onError(e -> emitters.remove(id));
        emitters.put(id, emitter);
        return emitter;
    }

    public void sendToEmitter(SseEmitter emitter, ModelStatsDto stats) {
        try {
            emitter.send(SseEmitter.event().name("stats-ready").data(objectMapper.writeValueAsString(stats)));
            emitter.complete();
        } catch (IOException e) {
            log.debug("SSE client disconnected before stats were sent");
        }
    }

    public void broadcastStats(ModelStatsDto stats) {
        String json;
        try {
            json = objectMapper.writeValueAsString(stats);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stats for broadcast", e);
            return;
        }
        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("stats-ready").data(json));
                emitter.complete();
            } catch (IOException e) {
                emitters.remove(id);
            }
        });
    }

    public void completeAllWithError() {
        emitters.forEach((id, emitter) -> {
            try {
                emitter.completeWithError(new RuntimeException("Stats computation failed"));
            } catch (Exception e) {
                // already closed
            }
            emitters.remove(id);
        });
    }

    @Scheduled(fixedRate = 25_000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        emitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitters.remove(id);
            }
        });
    }
}
