package com.ravi.stockscreener.scanservice;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ravi.stockscreener.model.*;
import com.ravi.stockscreener.repo.StrategyRepository;
import com.ravi.stockscreener.repo.Strategy_ResultRepo;
import com.ravi.stockscreener.util.SerialExecutor;
import kotlin.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.ravi.stockscreener.model.MarketDataFeed.MarketStatus.CLOSING_END;
import static com.ravi.stockscreener.model.MarketDataFeed.MarketStatus.CLOSING_START;
import static com.ravi.stockscreener.util.Utils.computeBucketStart;
import static com.ravi.stockscreener.util.Utils.timeframeToDuration;
import static org.springframework.http.HttpHeaders.ACCEPT;


@Service
@RequiredArgsConstructor
@Slf4j
public class UpstoxWebSocketService {

    private final Strategy_ResultRepo strategyResultRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InstrumentService instrumentService;
    private final StrategyRepository repo;
    public final UpstoxAuthService upstoxAuthService;
    private final HistoricalDataService historicalDataService;
    private final WebClient webClient;
    private final ScanService scanService;
    // class fields (add)
    private final NotificationService notificationService;
    private final AlertDeduper alertDeduper;
    //private final Gson gson;
    // read server.port and context-path if configured; fallbacks to 8080 and empty
    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${upstox.authorizeweb-url}")
    private String authorizeweb_url;

    @Value("${server.baseUrl}")
    private String baseUrl;

    private final ExecutorService sharedExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect-scheduler");
        t.setDaemon(true);
        return t;
    });


    // per-symbol serial executors and stores
    private final ConcurrentHashMap<String, SerialExecutor> symbolSerials = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UpstoxInstrument> instrumentIndex = new ConcurrentHashMap<>();
    private final List<Strategy> strategyList = new CopyOnWriteArrayList<>();

    // websocket & state
    private final AtomicReference<WebSocket> currentWebSocket = new AtomicReference<>(null);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);

    // reconnect policy
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final int maxReconnectAttempts = 8; // after this we go to cooldown
    private final long baseBackoffMillis = 1000L; // 1s base
    private final long maxBackoffMillis = 60_000L; // 1 minute cap
    private final long cooldownMillis = 5 * 60_000L; // after too many fails, wait 5 minutes
    private volatile ScheduledFuture<?> scheduledReconnect = null;
    private final Random jitterRandom = new Random();

    // simple metrics/counters
    private final AtomicInteger totalStarts = new AtomicInteger(0);
    private final AtomicInteger totalReconnects = new AtomicInteger(0);
    private final AtomicInteger totalFailures = new AtomicInteger(0);
    private final AtomicInteger totalCloses = new AtomicInteger(0);


    public Mono<String> fetchMarketDataAuthorizeUrl(String accessToken) {
        log.info("fetchMarketDataAuthorizeUrl -> accessToken: {}", accessToken);
        return webClient.get().uri(authorizeweb_url).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).accept(MediaType.APPLICATION_JSON).retrieve().onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class).defaultIfEmpty("")
                // help the compiler with the generic type:
                .flatMap(body -> Mono.error(new RuntimeException("Authorize feed failed: status=" + clientResponse.statusCode() + ", body=" + body)))).bodyToMono(JsonNode.class).flatMap(json -> {
            if (json != null && json.has("data") && json.get("data").has("authorized_redirect_uri")) {
                String url = json.get("data").get("authorized_redirect_uri").asText();
                log.info("Feed authorize HTTP success, url={}", url);
                return Mono.just(url);
            } else {
                String debug = json != null ? json.toString() : "<empty body>";
                log.warn("No authorized_redirect_uri in response: {}", debug);
                return Mono.error(new RuntimeException("No authorized_redirect_uri in response: " + debug));
            }
        }).doOnError(err -> log.error("Error fetching authorize URL", err));
    }

    public void connectAndSubscribeAll(List<UpstoxInstrument> upstoxInstruments) {
        instrumentIndex.clear();
        upstoxInstruments.forEach(inst -> instrumentIndex.put(inst.instrumentKey(), inst));

        // Load strategies
        repo.findAll()
                .map(entity -> {
                    try {
                        return objectMapper.readValue(entity.getPayload(), Strategy.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collectList()
                .doOnNext(list -> {
                    strategyList.clear();
                    strategyList.addAll(list);
                    log.info("Loaded {} strategies", list.size());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();


        upstoxAuthService.getCachedToken()
                // make sure token is present and non-blank
                .filter(token -> token != null && !token.isEmpty())
                // for each token get the authorize URL and then connect
                .flatMap(token -> fetchMarketDataAuthorizeUrl(token).map(s -> new Pair<>(token, s)))
                .doOnError(err -> log.error("Failed to connect and subscribe", err))
                // subscribe to start the whole flow — consider storing the Disposable if you want to cancel later
                .subscribe(new Consumer<Pair<String, String>>() {
                    @Override
                    public void accept(Pair<String, String> tokenurl) {
                        connectWebSocket(tokenurl.getFirst(), tokenurl.getSecond(), upstoxInstruments);
                    }
                });
    }

    private void connectWebSocket(String token, String uriStr, List<UpstoxInstrument> upstoxInstruments) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        Request request = new Request.Builder()
                .url(uriStr)
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .addHeader(ACCEPT, "*/*")
                .build();
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                log.info("WebSocket onOpen");
                currentWebSocket.set(webSocket);
                wsConnected.set(true);
                connecting.set(false);
                reconnectAttempts.set(0); // reset on success
                totalStarts.incrementAndGet();

                try {
                    byte[] payloadBytes = subscribePayLoad(upstoxInstruments, "full");
                    webSocket.send(ByteString.of(payloadBytes));
                } catch (Exception e) {
                    log.error("Failed to send subscribe payload", e);
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                log.info("WS text message: {}", text);
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                try {
                    MarketDataFeed.FeedResponse resp = MarketDataFeed.FeedResponse.parseFrom(bytes.asByteBuffer());
                    log.info("WS respe: {}", resp);
                    processTicks(resp);
                } catch (Exception e) {
                    log.error("Failed parsing protobuf feed", e);
                }
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                log.info("WebSocket closing: {} {}", code, reason);
                wsConnected.set(false);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                log.info("WebSocket closed: {} {}", code, reason);
                wsConnected.set(false);
                currentWebSocket.compareAndSet(webSocket, null);
                totalCloses.incrementAndGet();
                // schedule reconnect
                scheduleReconnect("closed", token, uriStr, upstoxInstruments);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                log.error("WebSocket failure", t);
                wsConnected.set(false);
                currentWebSocket.compareAndSet(webSocket, null);
                totalFailures.incrementAndGet();
                // schedule reconnect
                scheduleReconnect("failure", token, uriStr, upstoxInstruments);
            }
        };
        WebSocket ws = okHttpClient.newWebSocket(request, listener);
        // keep tentative reference (onOpen will set more stable state)
        currentWebSocket.set(ws);
    }


    // schedule reconnect with exponential backoff + jitter; thread-safe single scheduled task
    private synchronized void scheduleReconnect(String reason, String token, String uriStr, List<UpstoxInstrument> upstoxInstruments) {
        if (!started.get()) {
            log.info("Not scheduling reconnect because service is stopped (started=false). Reason: {}", reason);
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > maxReconnectAttempts) {
            log.warn("Max reconnect attempts ({}) exceeded. Entering cooldown for {} ms", maxReconnectAttempts, cooldownMillis);
            reconnectAttempts.set(0);
            // cancel any existing future
            if (scheduledReconnect != null && !scheduledReconnect.isDone()) {
                scheduledReconnect.cancel(false);
            }
            scheduledReconnect = reconnectScheduler.schedule(() -> {
                log.info("Cooldown ended, will attempt reconnect now");
                // do a fresh connect attempt: fetch token->url->connect
                attemptConnectEventually(upstoxInstruments);
            }, cooldownMillis, TimeUnit.MILLISECONDS);
            return;
        }
        long backoff = Math.min(maxBackoffMillis, baseBackoffMillis * (1L << (attempt - 1)));
        long jitter = jitterRandom.nextInt((int) Math.min(1000L, backoff)); // jitter up to 1s or backoff size
        long delay = backoff + jitter;

        log.warn("Scheduling reconnect attempt #{} in {} ms (reason: {})", attempt, delay, reason);
        totalReconnects.incrementAndGet();

        if (scheduledReconnect != null && !scheduledReconnect.isDone()) {
            // there's already a scheduled reconnect; we prefer to keep the earliest scheduled task
            log.debug("A reconnect task is already scheduled; skipping scheduling another");
            return;
        }

        scheduledReconnect = reconnectScheduler.schedule(() -> {
            log.info("Executing scheduled reconnect attempt #{}", attempt);
            attemptConnectEventually(upstoxInstruments);
        }, delay, TimeUnit.MILLISECONDS);
    }

    // attempt connect: get cached token -> authorize url -> connect
    private void attemptConnectEventually(List<UpstoxInstrument> upstoxInstruments) {
        // guard: only one connecting at a time
        if (!connecting.compareAndSet(false, true)) {
            log.info("Another connect attempt is in progress; skipping this one");
            return;
        }

        upstoxAuthService.getCachedToken()
                .filter(token -> token != null && !token.isEmpty())
                .flatMap(token -> fetchMarketDataAuthorizeUrl(token).map(url -> new AbstractMap.SimpleEntry<>(token, url)))
                .timeout(Duration.ofSeconds(30))
                .doOnError(err -> {
                    log.error("attemptConnectEventually failed to get token/authorize url", err);
                    connecting.set(false);
                })
                .subscribe(entry -> {
                    try {
                        connectWebSocket(entry.getKey(), entry.getValue(), upstoxInstruments);
                    } finally {
                        connecting.set(false);
                    }
                }, err -> {
                    log.error("Failed to attempt connect: {}", err.getMessage());
                    connecting.set(false);
                });
    }


    private void processTicks(MarketDataFeed.FeedResponse resp) {
        if (resp == null) return;
        // handle live_feed or initial_feed
        if (resp.getType() == MarketDataFeed.Type.live_feed || resp.getType() == MarketDataFeed.Type.initial_feed) {
            Map<String, MarketDataFeed.Feed> feeds = resp.getFeedsMap();
            for (Map.Entry<String, MarketDataFeed.Feed> e : feeds.entrySet()) {
                String instrumentKey = e.getKey();
                MarketDataFeed.Feed feed = e.getValue();

                // 1) simple ltpc message
                if (feed.hasLtpc()) {
                    MarketDataFeed.LTPC ltpc = feed.getLtpc();
                    processLtpc(instrumentKey, ltpc.getLtp(), ltpc.getCp(), ltpc.getLtq(), ltpc.getLtt());
                    continue;
                }

                // 2) fullFeed (marketFF or indexFF)
                if (feed.hasFullFeed()) {
                    MarketDataFeed.FullFeed ff = feed.getFullFeed();

                    // marketFF (detailed market)
                    if (ff.hasMarketFF()) {
                        MarketDataFeed.MarketFullFeed mff = ff.getMarketFF();
                        if (mff.hasLtpc()) {
                            MarketDataFeed.LTPC ltpc = mff.getLtpc();
                            processLtpc(instrumentKey, ltpc.getLtp(), ltpc.getCp(), ltpc.getLtq(), ltpc.getLtt());
                        }
                        // you can also read marketLevel, optionGreeks, etc.
                        continue;
                    }

                    // indexFF (index-specific full feed)
                    if (ff.hasIndexFF()) {
                        MarketDataFeed.IndexFullFeed iff = ff.getIndexFF();
                        if (iff.hasLtpc()) {
                            MarketDataFeed.LTPC ltpc = iff.getLtpc();
                            processLtpc(instrumentKey, ltpc.getLtp(), ltpc.getCp(), ltpc.getLtq(), ltpc.getLtt());
                        }
                        continue;
                    }
                }

                // 3) firstLevelWithGreeks (single-depth + greeks)
                if (feed.hasFirstLevelWithGreeks()) {
                    MarketDataFeed.FirstLevelWithGreeks fl = feed.getFirstLevelWithGreeks();
                    if (fl.hasLtpc()) {
                        MarketDataFeed.LTPC ltpc = fl.getLtpc();
                        processLtpc(instrumentKey, ltpc.getLtp(), ltpc.getCp(), ltpc.getLtq(), ltpc.getLtt());
                    }
                }
                // unknown/unsupported feed union - skip or log
            }
        }

        if (resp.getType() == MarketDataFeed.Type.market_info) {
            log.info("Received market_info, currentTs={}", resp.getCurrentTs());
            if (resp.getMarketInfo().getSegmentStatusMap().entrySet().stream()
                    .anyMatch(e -> (e.getKey().equals("NSE_EQ") || e.getKey().equals("NSE_INDEX"))
                            && (Objects.equals(e.getValue(), CLOSING_START) || Objects.equals(e.getValue(), CLOSING_END)))) {
                shutdown();
            }
        }

    }

    private void processLtpc(String instrumentKey, double ltp, double cp, long ltq, long ltt) {
        // replace with debug
        getExecutorForSymbol(instrumentKey).execute(() -> {
            ConcurrentHashMap<String, BaseBarSeries> seriesTimeframe = historicalDataService.seriesStore.get(instrumentKey);
            // log.info("seriesTimeframe {} processLtpc {} ltp={} ltq={}",seriesTimeframe, instrumentKey, ltp, ltq);
            if (!seriesTimeframe.isEmpty()) {
                updateSeriesAnalysis(seriesTimeframe, new Tick(instrumentKey, ltp, ltq, ltt));
            }
        });
    }

    private void updateSeriesAnalysis(ConcurrentHashMap<String, BaseBarSeries> seriesTimeframe, Tick tick) {
        if (seriesTimeframe == null || seriesTimeframe.isEmpty() || tick == null) return;

        for (Map.Entry<String, BaseBarSeries> entry : seriesTimeframe.entrySet()) {
            String tf = entry.getKey();
            BaseBarSeries series = entry.getValue();
            if (series == null) continue;
            long bucketStart = computeBucketStart(tick.timestamp, tf);
            Instant candleStart = Instant.ofEpochSecond(bucketStart);
            Duration period = timeframeToDuration(tf);
            updateBarSeries(series, candleStart, bucketStart, tick, period);
        }

    }


    private void checkStrategiesForInstrument(String instrumentKey, Tick tick, BarSeries series) {
        UpstoxInstrument inst = instrumentIndex.get(instrumentKey);
        if (inst == null) {
            log.debug("Instrument metadata not found for {}, skipping strategy eval", instrumentKey);
            return;
        }

        // evaluate strategies concurrently but bounded
        Flux.fromIterable(strategyList)
                // optional: filter strategies by segment / symbol if your Strategy has targeting fields
                //.filter(strategy -> strategyAppliesToInstrument(strategy, inst))
                .flatMap(strategy -> RxJava3Adapter.singleToMono(scanService.evaluateAllConditionsForInstrument(strategy, inst)).map(resultPair -> Tuples.of(strategy, resultPair)).onErrorResume(e -> {
                    log.error("Error evaluating strategy {} on {}: {}", strategy.getName(), instrumentKey, e.getMessage(), e);
                    // treat evaluation error as non-match
                    return Mono.just(Tuples.of(strategy, new Pair<>(false, List.of("evaluation error"))));
                }), 16) // concurrency: tune as needed
                .filter(tuple -> Boolean.TRUE.equals(tuple.getT2().getFirst())).doOnNext(tuple -> {
                    Strategy matchedStrategy = tuple.getT1();
                    List<String> debugNotes = tuple.getT2().getSecond();
                    try {
                        notifyAlert(matchedStrategy, inst, debugNotes, tick);
                    } catch (Exception e) {
                        log.error("notifyAlert failed for {} / {} : {}", matchedStrategy.getName(), instrumentKey, e.getMessage(), e);
                    }
                }).subscribeOn(Schedulers.boundedElastic()) // evaluate off caller thread / executor
                .subscribe(); // fire-and-forget; consider storing Disposable if you want cancellation
    }

    private void notifyAlert(Strategy matchedStrategy, UpstoxInstrument inst, List<String> debugNotes, Tick tick) {
        // owner / user id — replace with your Strategy field that identifies user
        log.info("ALERT strategy={} instrument={} tick={} notes={} thread={} ts={}", Objects.toString(matchedStrategy, "n/a"), Objects.toString(inst, "n/a"), tick, debugNotes, Thread.currentThread().getName(), Instant.now().toString());
        Strategy_Result strategyResult = new Strategy_Result();
        strategyResult.setStrategy_name(matchedStrategy.getName());
        strategyResult.setSymbol_name(inst.name());
        strategyResultRepo.save(strategyResult)
                .subscribe();
        /*String userId = matchedStrategy.getOwnerId(); // ensure Strategy has ownerId

        JSONObject payload = new JSONObject();
        payload.put("strategyId", matchedStrategy.getId());
        payload.put("strategyName", matchedStrategy.getName());
        payload.put("instrumentKey", inst.instrumentKey());
        payload.put("timestamp", tick.timestamp);
        payload.put("price", tick.price);
        payload.put("notes", debugNotes == null ? "" : String.join(",", debugNotes));

        String json = payload.toString();

        // dedupe: e.g. don't send same alert for same strategy+symbol within 30s
        alertDeduper.shouldSend(matchedStrategy.getId(), inst.instrumentKey(), 30)
                .doOnNext(allowed -> {
                    if (Boolean.TRUE.equals(allowed)) {
                        // push to user's WebSocket sink
                        notificationService.pushToUser(userId, json);

                        // Optional: persist into Redis "pending alerts" for offline clients
                        // reactiveRedis.opsForList().leftPush("alerts:pending:" + userId, json).subscribe();
                    } else {
                        log.debug("Duplicate alert skipped for {} / {}", matchedStrategy.getId(), inst.instrumentKey());
                    }
                })
                .doOnError(err -> log.error("dedupe check failed", err))
                .subscribe(); // fire-and-forget*/
    }


    private void updateBarSeries(BarSeries series, Instant candleStart, Long bucketStart, Tick tick, Duration period) {
        log.info("updateBarSeries START -> symbol={} time={} bucketStart={} period={} thread={}  candleStart{} ",
                tick.symbol, tick.timestamp, bucketStart, period, series.getBarCount(), candleStart);
        if (series.getLastBar().getEndTime().getEpochSecond() == bucketStart) {
            series.addTrade(tick.volume, tick.price);
            checkStrategiesForInstrument(tick.symbol, tick, series);
        } else {
            series.barBuilder().timePeriod(period).endTime(candleStart).openPrice(tick.price).highPrice(tick.price).lowPrice(tick.price).closePrice(tick.price).volume(tick.volume).amount(tick.price * tick.volume).add();
        }
    }


    // helper to get/create single-thread executor per symbol
    private Executor getExecutorForSymbol(String symbol) {
        return symbolSerials.computeIfAbsent(symbol, s -> new SerialExecutor(sharedExecutor));
    }


    public byte[] subscribePayLoad(List<UpstoxInstrument> instrumentKeys, String mode) throws JSONException {
        String guid = UUID.randomUUID().toString();
        JSONObject root = new JSONObject();
        root.put("guid", guid);
        root.put("method", "sub");
        JSONObject data = new JSONObject();
        data.put("mode", mode);
        JSONArray keys = new JSONArray();
        instrumentKeys.forEach(upstoxInstrument -> keys.put(upstoxInstrument.instrumentKey()));
        data.put("instrumentKeys", keys);
        root.put("data", data);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }


    // -----------------------
    // start / stop / status
    // -----------------------

    public Mono<ResponseEntity<String>> startWebSocket() {
        // idempotent checks
        if (!started.compareAndSet(false, true)) {
            return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body("WEBSOCKET ALREADY STARTED"));
        }
        totalStarts.incrementAndGet();

        if (!connecting.compareAndSet(false, true)) {
            started.set(true);
            return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body("WEBSOCKET ALREADY CONNECTING"));
        }

        return upstoxAuthService.getCachedToken()
                .timeout(Duration.ofSeconds(10))
                .flatMap(token -> {
                    log.info("token -> '{}'", token == null ? "<null>" : (token.isEmpty() ? "<empty>" : "present"));
                    startWithToken()
                            .subscribeOn(Schedulers.boundedElastic())   // IO-heavy काम अलग थ्रेड पर
                            .doOnError(err -> {
                                log.error("Background start failed", err);
                                connecting.set(false);
                                started.set(false);
                            })
                            .doOnSuccess(u -> log.info("Background startWithToken() completed"))
                            .subscribe(); // <-- यह जरूरी है: चलाने के लिए

                    return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body("WEBSOCKET STARTED"));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // no token -> restore flags and redirect to auth start
                    connecting.set(false);
                    started.set(false);
                    String url = buildLocalAuthStartUrl();
                    log.info("No valid token, redirecting to auth start: {}", url);
                    // redirect usually has no body; still ResponseEntity<String> is fine
                    return Mono.just(ResponseEntity.<String>status(HttpStatus.FOUND).location(URI.create(url)).build());
                }))
                .onErrorResume(e -> {
                    log.error("startWebSocket outer error", e);
                    connecting.set(false);
                    started.set(false);
                    return Mono.just(ResponseEntity.<String>status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: " + e.getMessage()));
                });
    }


    public String stopWebSocket() {
        // stop everything and cancel reconnects
        started.set(false);
        connecting.set(false);
        wsConnected.set(false);

        // cancel scheduled reconnect if present
        synchronized (this) {
            if (scheduledReconnect != null && !scheduledReconnect.isDone()) {
                scheduledReconnect.cancel(false);
                scheduledReconnect = null;
            }
        }
        // close websocket
        WebSocket ws = currentWebSocket.getAndSet(null);
        if (ws != null) {
            try {
                ws.close(1000, "manual-stop");
                log.info("WebSocket close requested via stopWebSocket");
            } catch (Exception e) {
                log.warn("Error closing websocket on stop", e);
            }
        }
        return "STOPPED";
    }


    public String getStatus() {
        return String.format("started=%s connecting=%s wsConnected=%s reconnectAttempts=%d totalStarts=%d totalReconnects=%d totalFailures=%d totalCloses=%d",
                started.get(), connecting.get(), wsConnected.get(), reconnectAttempts.get(), totalStarts.get(), totalReconnects.get(), totalFailures.get(), totalCloses.get());
    }

    public void shutdown() {
        stopWebSocket();
        reconnectScheduler.shutdownNow();
        sharedExecutor.shutdownNow();
    }


    private Mono<Void> startWithToken() {
        log.info("Access token found in Redis — loading symbols...");
        // instrumentService.streamInstruments() in your code returns a Mono<List<UpstoxInstrument>>
        return instrumentService.streamInstruments()
                .flatMap(upstoxInstruments -> {
                    // filter equity and limit to first element like original code
                    log.info("startWithToken upstoxInstruments {}", upstoxInstruments.size());
                    List<UpstoxInstrument> filtered = instrumentService.filterEquity(upstoxInstruments);
                    // run connectAndSubscribeAll (probably blocking / long-running) on boundedElastic
                    return Mono.fromRunnable(() -> {
                        try {
                            connectAndSubscribeAll(filtered);
                            log.info("Fetched {} instruments and started subscriptions", filtered.size());
                        } catch (Exception e) {
                            throw new RuntimeException("connectAndSubscribeAll failed", e);
                        }
                    });
                })
                .doOnError(err -> {
                    log.error("Failed to load instruments at startup", err);
                    started.set(false);
                    connecting.set(false);
                }).then();
    }


    private String buildLocalAuthStartUrl() {
        String path = contextPath == null ? "" : contextPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
        // ensure no trailing slash duplicate
        String base = baseUrl + path;
        // endpoint that starts OAuth flow
        return base + (base.endsWith("/") ? "" : "/") + "auth/start";
    }


}
