package com.ravi.stockscreener.scanservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ravi.stockscreener.model.UpstoxInstrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.ta4j.core.BaseBarSeriesBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentService {
    public static final String INSTRUMENTS_URL = "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz";
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redis;

    // cache key for JSON payload
    private static final String INSTRUMENT_CACHE_KEY = "INSTRUMENT_KEY:JSON";
    private static final Duration CACHE_TTL = Duration.ofDays(1); // adjust as needed


    // Download as byte[] (reactive) then parse on boundedElastic
    public Mono<List<UpstoxInstrument>> streamInstruments() {
        // 1) try cache
        return getInstrumentsFromCache()
                // if cache present -> return it
                .flatMap(cached -> {
                    if (cached != null && !cached.isEmpty()) {
                        log.debug("Returning instruments from cache (count={})", cached.size());
                        return Mono.just(cached);
                    } else {
                        return fetchAndCacheInstrumentsWithFallback();
                    }
                })
                // if no cache value at all, fallback to fetch
                .switchIfEmpty(fetchAndCacheInstrumentsWithFallback());
    }


    // --- Try read JSON from redis value and deserialize ---
    private Mono<List<UpstoxInstrument>> getInstrumentsFromCache() {
        return redis.opsForValue().get(INSTRUMENT_CACHE_KEY)
                .flatMap(json -> {
                    if (json.isBlank()) return Mono.empty();
                    return Mono.fromCallable(() ->
                                    mapper.readValue(json, new TypeReference<List<UpstoxInstrument>>() {}))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .onErrorResume(e -> {
                    log.warn("Failed to read/parse instruments from cache: {}", e.toString());
                    return Mono.empty(); // treat cache read failure as cache-miss
                });
    }


    // --- Fetch from network, parse, cache and return. On network error, if cache available return cache ---
    private Mono<List<UpstoxInstrument>> fetchAndCacheInstrumentsWithFallback() {
        // keep a snapshot of cache to use as fallback in case fetch fails
        Mono<List<UpstoxInstrument>> cachedFallback = getInstrumentsFromCache().cache();

        return webClient.get()
                .uri(INSTRUMENTS_URL)
                .retrieve()
                .bodyToMono(byte[].class)
                // retry with exponential backoff for transient network issues
                .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(throwable -> {
                            // optionally filter what errors to retry
                            return true;
                        }))
                .flatMap(bytes ->
                        Mono.fromCallable(() -> parseWithJackson(new ByteArrayInputStream(bytes)))
                                .subscribeOn(Schedulers.boundedElastic())
                )
        // cache the result in redis (as JSON) and return list
                .flatMap(list -> cacheInRedis(list)
                        .thenReturn(list)
                )
                // if network fails, try to return cachedFallback if present
                .onErrorResume(err -> {
                    log.warn("Failed to fetch instruments from network: {} — trying cache fallback", err.toString());
                    return cachedFallback.flatMap(fb -> {
                        if (fb == null || fb.isEmpty()) {
                            return Mono.error(new RuntimeException("Fetch failed and no cached instruments available", err));
                        } else {
                            log.info("Using stale cache as fallback (count={})", fb.size());
                            return Mono.just(fb);
                        }
                    });
                });
    }


    // --- Cache List<UpstoxInstrument> as JSON string in Redis with TTL ---
    private Mono<Boolean> cacheInRedis(List<UpstoxInstrument> list) {
        try {
            String json = mapper.writeValueAsString(list);
            return redis.opsForValue().set(INSTRUMENT_CACHE_KEY, json, CACHE_TTL)
                    .doOnSuccess(ok -> {
                        if (Boolean.TRUE.equals(ok)) log.debug("Instruments cached (count={})", list.size());
                        else log.warn("Redis set returned false");
                    })
                    .doOnError(e -> log.warn("Failed to cache instruments to redis: {}", e.toString()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize instruments for caching: {}", e.toString());
            return Mono.just(false);
        }
    }



    private List<UpstoxInstrument> parseWithJackson(InputStream inputStream) throws IOException {
        // BufferedInputStream so we can peek bytes and reset
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        bis.mark(4); // enough for magic bytes
        int b1 = bis.read();
        int b2 = bis.read();
        bis.reset();

        boolean gzipped = (b1 == 0x1f && b2 == 0x8b);

        // If gzipped -> wrap, otherwise use plain stream
        try (InputStream in = gzipped ? new GZIPInputStream(bis) : bis) {
            JsonNode root = mapper.readTree(in);
            List<UpstoxInstrument> out = new ArrayList<>();
            if (root == null) return out;
            if (root.isArray()) {
                for (JsonNode node : root) {
                    UpstoxInstrument inst = mapper.treeToValue(node, UpstoxInstrument.class);
                    out.add(inst);
                }
            } else if (root.isObject()) {
                Iterator<JsonNode> elements = root.elements();
                while (elements.hasNext()) {
                    JsonNode node = elements.next();
                    UpstoxInstrument inst = mapper.treeToValue(node, UpstoxInstrument.class);
                    out.add(inst);
                }
            } else {
                throw new IOException("Unsupported JSON root type: " + root.getNodeType());
            }
            return out;
        }
    }

    // store symbols reactively as Redis list
    public Mono<Long> storeSymbolsReactive(List<String> symbols, String key) {
        if (symbols == null || symbols.isEmpty()) return Mono.just(0L);
        return redis.opsForList().rightPushAll(key, symbols);
    }

    public Mono<List<String>> getSymbolsReactive(String key) {
        return redis.opsForList().range(key, 0, -1)
                .collectList();
    }


    public List<UpstoxInstrument> filterIndex(List<UpstoxInstrument> allInstruments) {
        return allInstruments.stream()
                .filter(ints -> ints.exchange().equals("NSE") && ints.instrumentType().equals("INDEX")).collect(Collectors.toList());
    }

    public static List<UpstoxInstrument> filterMtf(List<UpstoxInstrument> allInstruments) {
        return allInstruments.stream()
                .filter(ints -> Boolean.TRUE.equals(ints.mtfEnabled()) && ints.segment().equals("NSE_EQ") && ints.instrumentType().equals("EQ")).collect(Collectors.toList());
    }

    public List<UpstoxInstrument> filterEquity(List<UpstoxInstrument> allInstruments) {
        return allInstruments.stream()
                .filter(ints -> ints.segment().equals("NSE_EQ") && ints.instrumentType().equals("EQ")).collect(Collectors.toList());
    }

    public static List<UpstoxInstrument> filterFnoEquitySpot(List<UpstoxInstrument> allInstruments) {
        Set<String> namesSet = allInstruments.stream()
                .filter(inst -> "NSE_FO".equals(inst.segment()) &&
                        ("CE".equals(inst.instrumentType()) || "PE".equals(inst.instrumentType()))).map(UpstoxInstrument::name)       // map to underlying ticker
                .filter(Objects::nonNull).sorted().collect(Collectors.toCollection(LinkedHashSet::new));
        // collect all spot-equity names on NSE
        return allInstruments.stream()
                .filter(inst -> namesSet.contains(inst.name())
                        && inst.segment().equals("NSE_EQ")).collect(Collectors.toList());
    }


    public static List<UpstoxInstrument> filterMcx(List<UpstoxInstrument> allInstruments) {
        return allInstruments.stream()
                .filter(ints -> ints.exchange().equals("MCX")).collect(Collectors.toList());
    }


}
