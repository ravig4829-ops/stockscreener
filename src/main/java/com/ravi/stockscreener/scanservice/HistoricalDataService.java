package com.ravi.stockscreener.scanservice;


import com.ravi.stockscreener.model.UpstoxInstrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.ravi.stockscreener.util.Utils.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalDataService {

    private final ReactiveStringRedisTemplate redis;
    public static final List<String> DEFAULT_TIMEFRAMES = List.of("5minutes", "15minutes", "1hours", "4hours", "1days");

    //public static final Map<String, BaseBarSeries> Series_CACHE = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, ConcurrentHashMap<String, BaseBarSeries>> seriesStore = new ConcurrentHashMap<>();


    public void manageHistoricalData(List<UpstoxInstrument> list) {
        Flux.fromIterable(list)
                .flatMap(instrument ->
                                // for each instrument, process timeframes in sequence (or limited concurrency)
                                Flux.fromIterable(DEFAULT_TIMEFRAMES)
                                        // add a small delay between timeframe requests to avoid bursts
                                        .delayElements(Duration.ofSeconds(10))
                                        // per-instrument: use concatMap for strict sequential, or flatMap with concurrency
                                        .flatMap(tf -> ensureHistoricalData(instrument.instrumentKey(), tf)
                                                        .flatMap(aBoolean -> ensureCandleData(instrument, tf))
                                                        .map(baseBarSeries -> putSeriesMap(tf, baseBarSeries, instrument.instrumentKey()))
                                                , 100)
                                        .then()
                        , 100)
                .then()
                .doOnError(e -> log.error("manageHistoricalData error: {}", e.toString()))
                .subscribe(new Consumer<Void>() {
                    @Override
                    public void accept(Void unused) {
                        log.info("HistoricalData  Complete ..");
                    }
                });
    }

    private Mono<Boolean> ensureHistoricalData(String instrumentKey, String timeframe) {
        String redisKey = String.format("historical:%s:%s", instrumentKey, timeframe);
        return redis.hasKey(redisKey)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.info("Data already exists for {}:{} - skipping fetch", instrumentKey, timeframe);
                        return Mono.just(true);
                    } else {
                        log.info("Fetching data for {}:{}", instrumentKey, timeframe);
                        return fetchAndStoreHistoricalData(instrumentKey, timeframe)
                                .thenReturn(true);
                    }
                });
    }


    private Mono<Object> fetchAndStoreHistoricalData(String instrumentKey, String timeframe) {
        // compute date pair (whatever computeDateRange returns)
        Pair<String, String> range = computeDateRange(timeframe);
        // encode path segments (percent-encodes reserved chars like '|')
        String encodedInstrument = URLEncoder.encode(instrumentKey, StandardCharsets.UTF_8);
        String unit = URLEncoder.encode(timeframeToUnit(timeframe), StandardCharsets.UTF_8);
        String interval = URLEncoder.encode(timeframeToInterval(timeframe), StandardCharsets.UTF_8);
        // build path without leading extra slash problems
        String path = String.join("/",
                "v3",
                "historical-candle",
                encodedInstrument,
                unit,
                interval,
                range.getSecond(),
                range.getFirst());

        String baseUrl = "https://api.upstox.com"; // ensure no trailing slash
        String fullUrl = baseUrl + "/" + path;

        // proper log usage (placeholder) so it actually prints the URL
        log.info("full_url: {}", fullUrl);

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Accept", "application/json")
                .GET()
                .build();

        String redisKey = String.format("historical:%s:%s", instrumentKey, timeframe);

        return Mono.fromCallable(() -> httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(resp -> {
                    if (resp.statusCode() != 200) {
                        log.error("API request failed for {}:{} with status {}", instrumentKey, timeframe, resp.statusCode());
                        return Mono.empty();
                    }

                    String body = resp.body();
                    if (body == null || body.isEmpty()) {
                        log.warn("Empty response for {}:{}", instrumentKey, timeframe);
                        return Mono.empty();
                    }

                    return redis.opsForValue()
                            .setIfAbsent(redisKey, body, Duration.ofDays(1))
                            .flatMap(written -> {
                                if (Boolean.TRUE.equals(written)) {

                                    log.info("Stored historical JSON for {}:{}", instrumentKey, timeframe);
                                } else {
                                    log.info("Another instance already stored data for {}:{}", instrumentKey, timeframe);
                                }
                                return Mono.empty();
                            });

                })
                .onErrorResume(e -> {
                    log.error("Fetch failed for {}:{} - {}", instrumentKey, timeframe, e.getMessage());
                    return Mono.empty();
                });
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
                    series.barBuilder()
                            .timePeriod(timePeriod)
                            .endTime(OffsetDateTime.parse(c.getString(0), fmt).toInstant())
                            .openPrice(c.getDouble(1))
                            .highPrice(c.getDouble(2))
                            .lowPrice(c.getDouble(3))
                            .closePrice(c.getDouble(4))
                            .volume(c.getDouble(5))
                            .amount(0.0)
                            .add();
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


    public Mono<BaseBarSeries> ensureCandleData(UpstoxInstrument upstoxInstrument, String timeframe) {

        final String instrumentKey = upstoxInstrument.instrumentKey();
        final String redisKey = String.format("historical:%s:%s", instrumentKey, timeframe);

        ConcurrentHashMap<String, BaseBarSeries> cached = seriesStore.get(instrumentKey);
        if (cached != null) {
            return Mono.justOrEmpty(cached.get(timeframe));
        }
        // cache only non-empty series (best-effort)
        return redis.opsForValue().get(redisKey)
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Data not found in Redis for {}:{}, fetching...", instrumentKey, timeframe);
                    return fetchAndStoreHistoricalData(instrumentKey, timeframe)
                            .then(redis.opsForValue().get(redisKey));
                }))
                .map(json -> createCandles(json, timeframe, upstoxInstrument.instrumentKey()))
                .flatMap(series -> {
                    if (series == null) series = new BaseBarSeriesBuilder().withName(upstoxInstrument.name()).build();
                    // cache only non-empty series (best-effort)
                    if (series.getBarCount() > 0) {
                        try {
                            series = putSeriesMap(timeframe, series, instrumentKey);
                        } catch (Exception ignored) {
                        }
                    }
                    return Mono.just(series);
                })
                .defaultIfEmpty(new BaseBarSeriesBuilder().withName(instrumentKey + ":" + timeframe).build());
    }

    private BaseBarSeries putSeriesMap(String timeframe, BaseBarSeries series, String instrumentKey) {
        ConcurrentHashMap<String, BaseBarSeries> innerMap = seriesStore.computeIfAbsent(instrumentKey, k -> new ConcurrentHashMap<>());
        return innerMap.putIfAbsent(timeframe, series);
    }


}
