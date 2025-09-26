package com.ravi.stockscreener.controller;


import com.google.gson.Gson;
import com.ravi.stockscreener.model.Strategy;
import com.ravi.stockscreener.model.Strategys;
import com.ravi.stockscreener.model.UpstoxInstrument;
import com.ravi.stockscreener.repo.StrategyRepository;
import com.ravi.stockscreener.scanservice.InstrumentService;
import com.ravi.stockscreener.scanservice.ScanService;
import com.ravi.stockscreener.scanservice.UpstoxWebSocketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class ScanController {
    private final ScanService scanService;
    private final InstrumentService instrumentService;
    public final UpstoxWebSocketService upstoxWebSocketService;
    private final StrategyRepository repo;
    private final Gson gson;


    @PostMapping("/scan")
    public Mono<ResponseEntity<String>> scan(@RequestBody @Valid Strategy scanRequest, @RequestParam(defaultValue = "false") boolean backgroundScan) {
        return scanService.scan(scanRequest, backgroundScan);
    }

    @PostMapping("/strategies")
    public Mono<ResponseEntity<String>> create(@RequestBody Strategy strategy) {
        String strategysJson = gson.toJson(strategy, Strategy.class);
        log.info("  strategys {}", strategysJson);
        return repo.existsByPayload(strategysJson)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                                .body("Strategy already exists with id: " + strategy.getId()));
                    }
                    // not exists -> save and return CREATED with Location header
                    Strategys strategys1 = new Strategys();
                    strategys1.setPayload(strategysJson);

                    return repo.save(strategys1)
                            .map(saved -> ResponseEntity
                                    .created(URI.create("/strategies/" + saved.getId()))
                                    .body("Strategy created with id: " + saved.getId()));
                })
                // handle specific validation error first
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.badRequest().body("Invalid request: " + e.getMessage())))
                // general fallback
                .onErrorResume(e -> {
                    log.error("Failed to create strategy", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Internal error: " + e.getMessage()));
                });
    }


    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }


    @GetMapping(value = "/instruments/mcx", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<List<UpstoxInstrument>>> streamMcxInstruments() {
        return instrumentService.streamInstruments().map(ResponseEntity::ok).onErrorResume(this::handleError);
    }

    private <T> Mono<ResponseEntity<T>> handleError(Throwable error) {
        log.error("Unexpected error", error);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }


    @GetMapping("/start")
    public Mono<ResponseEntity<String>> start() {
        return upstoxWebSocketService.startWebSocket()
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("start endpoint failed", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: " + e.getMessage()));
                });
    }

    @GetMapping("/stop")
    public Mono<ResponseEntity<String>> stop() {
        try {
            String msg = upstoxWebSocketService.stopWebSocket();
            return Mono.just(ResponseEntity.ok(msg));
        } catch (Exception e) {
            log.error("stop endpoint failed", e);
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public Mono<ResponseEntity<String>> status() {
        return Mono.just(ResponseEntity.ok(upstoxWebSocketService.getStatus()));
    }

}

