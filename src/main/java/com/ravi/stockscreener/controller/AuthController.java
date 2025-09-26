package com.ravi.stockscreener.controller;

import com.ravi.stockscreener.scanservice.UpstoxAuthService;
import com.ravi.stockscreener.scanservice.UpstoxWebSocketService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;


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
    public RedirectView startAuth(HttpSession session) {
        String state = UUID.randomUUID().toString();
        session.setAttribute("oauth_state", state);
        String url = buildAuthUrl(state);
        RedirectView rv = new RedirectView(url);
        rv.setExposeModelAttributes(false);
        return rv;
    }

    @GetMapping("/auth/callback")
    public Mono<String> callback(String code, String state, HttpSession session, Model model) {
        // synchronous session access is OK (servlet API). remove attribute immediately.
        String expected = (String) session.getAttribute("oauth_state");
        session.removeAttribute("oauth_state");

        if (expected == null || !expected.equals(state)) {
            model.addAttribute("error", "Invalid state (possible CSRF).");
            // return Mono.just so caller thread isn't blocked
            return Mono.just("auth/error");
        }
        if (code == null || code.isBlank()) {
            model.addAttribute("error", "Missing code in callback.");
            return Mono.just("auth/error");
        }

        // call reactive exchange method and map to view name when done
        return authService.exchangeCodeForTokenAndCacheReactive(code)
                .flatMap(aBoolean -> upstoxWebSocketService.startWebSocket())
                .then(Mono.fromSupplier(() -> {
                    model.addAttribute("message", "Login successful — token cached.");
                    return "auth/success";
                }))
                .onErrorResume(ex -> {
                    log.error("Token exchange failed", ex);
                    model.addAttribute("error", "Token exchange failed: " + ex.getMessage());
                    return Mono.just("auth/error");
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
