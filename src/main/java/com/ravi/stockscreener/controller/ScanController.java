package com.ravi.stockscreener.controller;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ravi.stockscreener.model.Strategy;
import com.ravi.stockscreener.model.Strategys;
import com.ravi.stockscreener.model.UpstoxInstrument;
import com.ravi.stockscreener.repo.HistoricalCandleRepo;
import com.ravi.stockscreener.repo.StrategyRepository;
import com.ravi.stockscreener.scanservice.InstrumentService;
import com.ravi.stockscreener.scanservice.ScanService;
import com.ravi.stockscreener.scanservice.UpstoxWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class ScanController {
    private final ScanService scanService;
    private final InstrumentService instrumentService;
    public final UpstoxWebSocketService upstoxWebSocketService;
    private final StrategyRepository repo;
    //   private final Gson gson;
    private final HistoricalCandleRepo historicalRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String historicalKey(String instrumentKey, String timeframe) {
        Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        return String.format("historical:%s:%s", instrumentKey, timeframe);
    }

    @PostMapping(value = "/scan-stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> scanStream(@RequestBody Strategy strategy,
                                                    @RequestParam(defaultValue = "false") boolean backgroundScan) {
        return scanService.startScanStream(strategy, backgroundScan);
    }

    @GetMapping("/history")
    public Mono<ResponseEntity<String>> getHistory(@RequestParam String instrumentKey,
                                                   @RequestParam String timeframe) {
        String key = historicalKey(instrumentKey, timeframe);

        final String noMatchesBody = new JSONObject()
                .put("success", "false")
                .put("data", "No matches found")
                .toString();

        return historicalRepo.findByKey(key)
                .next()   // <-- converts Flux<T> to Mono<T> by emitting the first item (or empty)
                .map(baseBarSeries -> {
                    String json = baseBarSeries.getJson();
                    JSONObject obj = new JSONObject();
                    if (json != null && !json.isEmpty()) {
                        obj.put("success", "true");
                        obj.put("data", json);
                    } else {
                        obj.put("success", "false");
                        obj.put("data", "No matches found");
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(obj.toString());
                })
                .defaultIfEmpty(ResponseEntity.ok(noMatchesBody))
                .onErrorResume(e -> {
                    log.error("history failed", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("history failed: " + e.getMessage()));
                });
    }


    @PostMapping("/strategies")
    public Mono<ResponseEntity<String>> create(@RequestBody Strategy strategy) {
        String strategysJson = null;
        try {
            strategysJson = objectMapper.writeValueAsString(strategy);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        log.info("  strategys {}", strategysJson);

        String finalStrategysJson = strategysJson;
        return repo.existsByPayload(strategysJson)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                                .body("Strategy already exists with id: " + strategy));
                    }
                    // not exists -> save and return CREATED with Location header
                    Strategys strategys1 = new Strategys();
                    strategys1.setPayload(finalStrategysJson);

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
        return upstoxWebSocketService.startWebSocket();
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

