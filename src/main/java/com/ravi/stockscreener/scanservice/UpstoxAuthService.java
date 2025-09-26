package com.ravi.stockscreener.scanservice;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpstoxAuthService {
    private final WebClient webClient; // injected from RedisConfig
    private final ReactiveStringRedisTemplate redis;


    @Value("${upstox.client-id}")
    private String clientId;

    @Value("${upstox.client-secret}")
    private String clientSecret;

    @Value("${upstox.redirect-uri}")
    private String redirectUri;

    @Value("${upstox.token-url}")
    private String tokenUrl;


    private String accessTokenKey() {
        return "upstox:access_token";
    }


    public Mono<Boolean> exchangeCodeForTokenAndCacheReactive(String code) {
        return webClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("code", code)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(body -> {
                    JSONObject json = new JSONObject(body);
                    if (!json.has("access_token")) {
                        return Mono.error(new IllegalStateException("No access_token in response"));
                    }
                    String accessToken = json.getString("access_token");
                    Duration ttl = ttlUntilNextMorning330();
                    return redis.opsForValue().set(accessTokenKey(), accessToken, ttl)
                            .map(pubCount -> true);
                })
                .doOnSuccess(v -> log.info("Token exchanged and cached"))
                .doOnError(err -> log.error("Token exchange failed", err));
    }


    // compute Duration until next morning 03:30 Asia/Kolkata
    private Duration ttlUntilNextMorning330() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        LocalTime targetTime = LocalTime.of(3, 30);
        ZonedDateTime target = ZonedDateTime.of(tomorrow, targetTime, zone);
        long seconds = ChronoUnit.SECONDS.between(now, target);
        if (seconds <= 0) seconds = 1;
        return Duration.ofSeconds(seconds);
    }

    // reactive getter: returns Mono<String> (null or empty if not present)
    public Mono<String> getCachedToken() {
        return redis.opsForValue()
                .get(accessTokenKey())
                .defaultIfEmpty("");// Mono<String>
    }


}

