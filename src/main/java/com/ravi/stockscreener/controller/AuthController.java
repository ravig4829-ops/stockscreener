package com.ravi.stockscreener.controller;

import com.ravi.stockscreener.scanservice.UpstoxAuthService;
import com.ravi.stockscreener.scanservice.UpstoxWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;


import java.net.URI;
import java.util.Objects;
import java.util.UUID;


import okhttp3.HttpUrl;
import reactor.core.publisher.Mono;

@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    @Value("${upstox.authorize-url}")
    private String authorizeUrl;

    @Value("${upstox.client-id}")
    private String clientId;

    @Value("${upstox.redirect-uri}")
    private String redirectUri;

    private final UpstoxAuthService authService;
    private final UpstoxWebSocketService upstoxWebSocketService;

    // start OAuth: redirect to provider
    @GetMapping("/auth/start")
    public Mono<ResponseEntity<Void>> startAuth(ServerWebExchange exchange) {
        return exchange.getSession()
                .map(webSession -> {
                    String state = UUID.randomUUID().toString();
                    webSession.getAttributes().put("oauth_state", state);
                    String url = buildAuthUrl(state);
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create(url))
                            .build();
                });
    }


    @GetMapping("/auth/callback")
    public Mono<String> callback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 ServerWebExchange exchange,
                                 Model model) {

        return exchange.getSession()
                .flatMap(webSession -> {
                    String expected = (String) webSession.getAttributes().get("oauth_state");
                    webSession.getAttributes().remove("oauth_state");

                    if (expected == null || !expected.equals(state)) {
                        model.addAttribute("error", "Invalid state (possible CSRF).");
                        return Mono.just("auth/error");
                    }
                    if (code == null || code.isBlank()) {
                        model.addAttribute("error", "Missing code in callback.");
                        return Mono.just("auth/error");
                    }
                    // exchange code for token (reactive) then start websocket (reactive)
                    return authService.exchangeCodeForTokenAndCacheReactive(code)
                            .flatMap(success -> {
                                // ensure startWebSocket returns Mono<Void> or Mono<Boolean>
                                return upstoxWebSocketService.startWebSocket();
                            })
                            .then(Mono.fromSupplier(() -> {
                                model.addAttribute("message", "Login successful — token cached.");
                                return "auth/success";
                            }))
                            .onErrorResume(ex -> {
                                log.error("Token exchange failed", ex);
                                model.addAttribute("error", "Token exchange failed: " + ex.getMessage());
                                return Mono.just("auth/error");
                            });
                });
    }


    private String buildAuthUrl(String state) {
        HttpUrl.Builder b = Objects.requireNonNull(HttpUrl.parse(authorizeUrl)).newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirectUri);
        if (state != null && !state.isBlank()) b.addQueryParameter("state", state);
        return b.build().toString();
    }
}
