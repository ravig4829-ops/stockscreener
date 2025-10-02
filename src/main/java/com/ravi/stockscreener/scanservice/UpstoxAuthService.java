package com.ravi.stockscreener.scanservice;


import com.ravi.stockscreener.model.User;
import com.ravi.stockscreener.repo.UserRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class UpstoxAuthService {
    private final WebClient webClient; // injected from RedisConfig
    private final UserRepo userRepo;


    @Value("${upstox.client-id}")
    private String clientId;

    @Value("${upstox.client-secret}")
    private String clientSecret;

    @Value("${upstox.redirect-uri}")
    private String redirectUri;

    @Value("${upstox.token-url}")
    private String tokenUrl;
    private static final Duration SAFETY_BUFFER = Duration.ofSeconds(30); // avoid returning near-expiry tokens


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
                    User user = new User();
                    user.setAccesstoken(accessToken);
                    user.setExpiry(nextMorning330Instant());
                    // delete all previous users, then save the new one
                    return userRepo.deleteAll()
                            .then(userRepo.save(user))
                            .doOnSuccess(saved -> log.info("Saved new token user: {}", saved))
                            .thenReturn(true);
                })
                .doOnSuccess(v -> log.info("Token exchanged and cached"))
                .doOnError(err -> log.error("Token exchange failed", err))
                .onErrorReturn(false);
    }


    // compute Duration until next morning 03:30 Asia/Kolkata
    public Instant nextMorning330Instant() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        LocalTime targetTime = LocalTime.of(3, 30);
        ZonedDateTime target = ZonedDateTime.of(tomorrow, targetTime, zone);
        return target.toInstant();
    }


    public Mono<String> getCachedToken() {
        return userRepo.findAll().next()
                .flatMap(user -> {
                    log.info("  user  {}", user);
                    String token = user.getAccesstoken();
                    Instant expiryStr = user.getExpiry();

                    if (token == null || token.isEmpty() || expiryStr == null) {
                        return Mono.empty();
                    }
                    Instant now = Instant.now();
                    if (now.plus(SAFETY_BUFFER).isBefore(expiryStr)) {
                        return Mono.just(token);
                    } else {
                        log.info("Access token expired or too close to expiry (expiry={}, now={})", expiryStr, now);
                        return Mono.empty();
                    }
                }).onErrorResume(err -> {
                    log.error("getCachedToken DB error — returning empty so caller can handle auth flow", err);
                    return Mono.empty();
                });
    }


}

