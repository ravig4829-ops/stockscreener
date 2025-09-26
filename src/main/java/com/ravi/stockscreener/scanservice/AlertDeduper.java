package com.ravi.stockscreener.scanservice;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AlertDeduper {
    private final ReactiveStringRedisTemplate redis;

    // returns Mono<Boolean> true if allowed to send (not duplicate)
    public Mono<Boolean> shouldSend(String strategyId, String instrumentKey, long ttlSeconds) {
        String key = "alert:dedupe:" + strategyId + ":" + instrumentKey;
        return redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
    }
}

