package com.ravi.stockscreener.scanservice;


import com.ravi.stockscreener.model.HistoricalCandle;
import com.ravi.stockscreener.model.UpstoxInstrument;
import com.ravi.stockscreener.repo.HistoricalCandleRepo;

import com.ravi.stockscreener.util.HttpResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.data.util.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ravi.stockscreener.util.Utils.*;
import static org.springframework.http.HttpHeaders.RETRY_AFTER;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataService {

    private final HistoricalCandleRepo historicalRepo;
    public static final List<String> DEFAULT_TIMEFRAMES = List.of("5minutes", "15minutes", "1hours", "4hours", "1days");
    //public static final List<String> DEFAULT_TIMEFRAMES = List.of("5minutes");

    // optional: tune concurrency constants
    private static final int INSTRUMENT_CONCURRENCY = 4; // reduce from 20
    private static final int TF_CONCURRENCY = 2;

    public ConcurrentHashMap<String, ConcurrentHashMap<String, BaseBarSeries>> seriesStore = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 4;  // total extra retries (you can tune)
    private static final long DEFAULT_RETRY_SECONDS = 5L;
    private final WebClient webClient; // injected via constructor


    // helper to compute backoff with jitter
    private Duration backoffWithJitter(long attempt, long baseSeconds, long maxSeconds) {
        long exp = Math.min(maxSeconds, baseSeconds * (1L << Math.min(10, (int) attempt))); // cap exponent
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, exp / 4));
        return Duration.ofSeconds(Math.max(1L, exp + jitter));
    }


    public void manageHistoricalData(List<UpstoxInstrument> list) {
        Flux.fromIterable(list).flatMap(instrument ->
                        // for each instrument, process timeframes in sequence (or limited concurrency)
                        Flux.fromIterable(DEFAULT_TIMEFRAMES)
                                .flatMap(tf -> fetchHistoricalData(instrument.instrumentKey(), tf)
                                        , INSTRUMENT_CONCURRENCY), TF_CONCURRENCY)
                .doOnError(e -> log.error("manageHistoricalData error: {}", e.toString()))
                .doOnComplete(() -> log.info("HistoricalData Complete .."))
                .collectList()
                .flatMapMany(historicalRepo::saveAll)
                .subscribe(new Consumer<HistoricalCandle>() {
                    @Override
                    public void accept(HistoricalCandle historicalCandle) {
                        log.info("HistoricalData subscribe ..{}", historicalCandle);
                    }
                });
    }

    public String historicalKey(String instrumentKey, String timeframe) {
        Objects.requireNonNull(instrumentKey, "instrumentKey must not be null");
        Objects.requireNonNull(timeframe, "timeframe must not be null");
        return String.format("historical:%s:%s", instrumentKey, timeframe);
    }

    private HistoricalCandle createHistoricalCandleEntity(String json, String key) {
        HistoricalCandle entity = new HistoricalCandle();
        entity.setJson(json);
        entity.setKey(key);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private void updateSeriesStore(HistoricalCandle candle, String instrumentKey, String timeframe) {
        try {
            BaseBarSeries series = createCandles(candle.getJson(), timeframe, instrumentKey);
            putSeriesMap(timeframe, series, instrumentKey);
            log.debug("Updated series store for {}:{} with {} bars", instrumentKey, timeframe, series.getBarCount());
        } catch (Exception e) {
            log.warn("Failed to update series store for {}:{} - {}", instrumentKey, timeframe, e.getMessage());
        }
    }


    private Mono<HistoricalCandle> fetchHistoricalData(String instrumentKey, String timeframe) {
        Pair<String, String> range = computeDateRange(timeframe);
        String to = range.getSecond();
        String from = range.getFirst();
        URI built = UriComponentsBuilder.fromPath("/v3/historical-candle/{instrument}/{unit}/{interval}/{to}/{from}").buildAndExpand(instrumentKey, timeframeToUnit(timeframe), timeframeToInterval(timeframe), to, from).encode().toUri();
        log.info("Built URI: {}", built);
        String redisKey = historicalKey(instrumentKey, timeframe);

        return webClient.get().uri(uriBuilder -> uriBuilder.path("/v3/historical-candle/{instrument}/{unit}/{interval}/{to}/{from}")
                        .build(instrumentKey, timeframeToUnit(timeframe), timeframeToInterval(timeframe), to, from))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(resp -> {
                    int status = resp.statusCode().value();
                    return resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> {
                        if (status >= 200 && status < 300) {
                            if (body.isBlank()) {
                                return Mono.error(new RuntimeException("Empty body from upstream " + built));
                            }
                            return Mono.just(body);
                        } else {
                            // capture headers so we can inspect Retry-After downstream
                            HttpHeaders headers = resp.headers().asHttpHeaders();
                            return Mono.error(new HttpResponseException(status, headers, body));
                        }
                    });
                })
                .retryWhen(createRetrySpec(instrumentKey, timeframe))
                .map(json -> createHistoricalCandleEntity(json, redisKey))
                .doOnNext(candle -> updateSeriesStore(candle, instrumentKey, timeframe))
                .onErrorResume(e -> {
                    log.error("Failed to fetch historical data for {}:{} - {}", instrumentKey, timeframe, e.getMessage());
                    return Mono.empty(); // Continue with other items on error
                });
    }

    @NotNull
    private Retry createRetrySpec(String instrumentKey, String timeframe) {
        return Retry.from(companion -> companion.flatMap((Retry.RetrySignal rs) -> {
            Throwable t = rs.failure();
            long retryCount = rs.totalRetries();
            if (retryCount >= MAX_RETRIES) {
                log.warn("Max retries reached for 429. Propagating error for {}:{}", instrumentKey, timeframe);
                return Mono.error(t);
            }
            // If we got a HTTP-status wrapper
            if (t instanceof HttpResponseException hre) {
                int status = hre.status();
                if (status == 429) {
                    String raf = hre.headers().getFirst(RETRY_AFTER);
                    long waitSeconds = parseRetryAfterSeconds(raf, DEFAULT_RETRY_SECONDS);
                    log.info("Received 429 for {}:{}. Waiting {}s (attempt {}).", instrumentKey, timeframe, waitSeconds, rs.totalRetries() + 1);
                    return Mono.delay(Duration.ofSeconds(waitSeconds));
                }
                // other 4xx -> don't retry
                if (status >= 400 && status < 500) {
                    log.warn("Non-retriable 4xx ({}) for {}:{}. Propagating.", status, instrumentKey, timeframe);
                    return Mono.error(t);
                }
                // for 5xx -> fall through to exponential backoff
            }

            // handle network / connection reset / timeouts
            Throwable root = Exceptions.unwrap(t);
            boolean isNetworkError = root instanceof java.io.IOException || root instanceof org.springframework.web.reactive.function.client.WebClientRequestException;

            if (isNetworkError) {
                Duration wait = backoffWithJitter(retryCount, DEFAULT_RETRY_SECONDS, 60L);
                log.info("Network error for {}:{} -> retry in {}s (attempt {}) : {}", instrumentKey, timeframe, wait.getSeconds(), retryCount + 1, root.toString());
                return Mono.delay(wait);
            }
            return Mono.error(t);
        }));
    }


    // 1) improved parseRetryAfterSeconds
    private long parseRetryAfterSeconds(String headerValue, long fallbackSeconds) {
        if (headerValue == null || headerValue.isBlank()) {
            log.debug("parseRetryAfterSeconds: header blank -> fallback {}s", fallbackSeconds);
            return fallbackSeconds;
        }
        String v = headerValue.trim();
        // try plain integer (allow possible decimal or trailing chars)
        // extract leading number token
        try {
            // keep only leading digits (and optional sign)
            Matcher m = Pattern.compile("^\\s*([0-9]+)").matcher(v);
            if (m.find()) {
                return Math.max(0L, Long.parseLong(m.group(1)));
            }
        } catch (NumberFormatException ignored) {
            // fallthrough to next attempt
        }

        // try RFC1123 date parse
        try {
            ZonedDateTime date = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long secs = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), date).getSeconds();
            return Math.max(0L, secs);
        } catch (Exception ex) {
            log.debug("Could not parse Retry-After header '{}' as RFC1123: {}", v, ex.getMessage());
            return fallbackSeconds;
        }
    }


    private BaseBarSeries createCandles(String json, String timeframe, String instrumentKey) {
        BaseBarSeries series = new BaseBarSeriesBuilder().withName(instrumentKey + ":" + timeframe).build();
        try {
            JSONObject root = new JSONObject(json);
            if (!"success".equalsIgnoreCase(root.getString("status"))) {
                log.warn("API returned non-success status for {}:{}", instrumentKey, timeframe);
                return series;
            }

            JSONObject data = root.getJSONObject("data");
            JSONArray candlesArr = data.getJSONArray("candles");

            List<JSONArray> items = new ArrayList<>(candlesArr.length());
            for (int i = 0; i < candlesArr.length(); i++) {
                items.add(candlesArr.getJSONArray(i));
            }

            DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            items.sort(Comparator.comparing(o -> OffsetDateTime.parse(o.getString(0), fmt)));
            Duration timePeriod = timeframeToDuration(timeframe);
            for (JSONArray c : items) {
                try {
                    series.barBuilder().timePeriod(timePeriod).endTime(OffsetDateTime.parse(c.getString(0), fmt).toInstant()).openPrice(c.getDouble(1)).highPrice(c.getDouble(2)).lowPrice(c.getDouble(3)).closePrice(c.getDouble(4)).volume(c.getDouble(5)).amount(0.0).add();
                } catch (Exception e) {
                    log.warn("Failed to parse candle for {}:{} - {}", instrumentKey, timeframe, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to create candles for {}:{} - {}", instrumentKey, timeframe, e.getMessage());
        }
        log.info("Created {} bars for {}:{}", series.getBarCount(), instrumentKey, timeframe);
        return series;
    }


    public Mono<BaseBarSeries> ensureCandleData(String instrumentKey, String timeframe) {
       // final String instrumentKey = upstoxInstrument.instrumentKey();
        final String redisKey = historicalKey(instrumentKey, timeframe);

        ConcurrentHashMap<String, BaseBarSeries> cached = seriesStore.get(instrumentKey);
        if (cached != null) {
            return Mono.justOrEmpty(cached.get(timeframe));
        }
        // cache only non-empty series (best-effort)
        // try DB first; if absent, fetch & save, then use the entity.json
        return historicalRepo.findByKey(redisKey).next() // Flux -> Mono of first element (or empty)
                .map(entity -> {
                    // entity is HistoricalCandle
                    BaseBarSeries series = createCandles(entity.getJson(), timeframe, instrumentKey);
                    if (series.getBarCount() > 0) {
                        putSeriesMap(timeframe, series, instrumentKey);
                    }
                    return series;
                }).switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("No DB entry for {}:{} — returning empty BaseBarSeries (no upstream fetch)", instrumentKey, timeframe);
                    return new BaseBarSeriesBuilder().withName(instrumentKey + ":" + timeframe).build();
                }));
    }

    private BaseBarSeries putSeriesMap(String timeframe, BaseBarSeries series, String instrumentKey) {
        ConcurrentHashMap<String, BaseBarSeries> innerMap = seriesStore.computeIfAbsent(instrumentKey, k -> new ConcurrentHashMap<>());
        return innerMap.putIfAbsent(timeframe, series);
    }


}
