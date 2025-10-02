package com.ravi.stockscreener.scanservice;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlertDeduper {


    // returns Mono<Boolean> true if allowed to send (not duplicate)
   /* public Mono<Boolean> shouldSend(String strategyId, String instrumentKey, long ttlSeconds) {
        String key = "alert:dedupe:" + strategyId + ":" + instrumentKey;
        return redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
    }*/
}

