package com.ravi.stockscreener.scanservice;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {
    // per-user in-memory sinks. Multi-node -> use Redis pub/sub bridge.
    private final ConcurrentHashMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    public Sinks.Many<String> sinkFor(String userId) {
        return sinks.computeIfAbsent(userId, k ->
                Sinks.many().multicast().onBackpressureBuffer(256, false)
        );
    }

    public void pushToUser(String userId, String jsonPayload) {
        Sinks.Many<String> sink = sinkFor(userId);
        Sinks.EmitResult result = sink.tryEmitNext(jsonPayload);
        if (result.isFailure()) {
            // optional: log the failure reason
            System.out.println("pushToUser emit failed: " + result);
        }
    }

    public void removeSink(String userId) {
        sinks.remove(userId);
    }
}

