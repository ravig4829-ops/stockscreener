package com.ravi.stockscreener.scanservice;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
@RequiredArgsConstructor
public class AlertsWebSocketHandler implements WebSocketHandler {

    private final NotificationService notificationService;

    @NotNull
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // extract userId from path: /ws/alerts/{userId}
        String path = session.getHandshakeInfo().getUri().getPath();
        // basic extraction (improve in prod)
        String userId = path.substring(path.lastIndexOf('/') + 1);

        Sinks.Many<String> sink = notificationService.sinkFor(userId);

        Flux<WebSocketMessage> outgoing = sink.asFlux()
                .map(session::textMessage)
                .doFinally(sig -> notificationService.removeSink(userId));

        Mono<Void> inbound = session.receive()
                .doOnNext(msg -> {
                    // optional: handle client messages (acks/pings)
                }).then();

        return session.send(outgoing).and(inbound);
    }
}

