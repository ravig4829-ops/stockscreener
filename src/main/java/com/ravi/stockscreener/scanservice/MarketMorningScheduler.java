package com.ravi.stockscreener.scanservice;


import com.ravi.stockscreener.model.UpstoxInstrument;
import com.ravi.stockscreener.repo.HistoricalCandleRepo;
import com.ravi.stockscreener.repo.InstrumentRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketMorningScheduler {

    public final InstrumentService instrumentService;
    private final WebClient webClient;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final String MARKET_STATUS_URL_TEMPLATE = "https://api.upstox.com/v2/market/timings/{date}";
    private final HistoricalDataService historicalDataService;
    private final HistoricalCandleRepo historicalCandleRepo;
    private final InstrumentRepo instrumentRepo;

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
    public void runDailyMorningCheck() {
        startApp();
    }

    public void startApp() {
        String date = LocalDate.now(ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE); // yyyy-MM-dd
        log.info("Running morning market check for {} at {}", date, ZonedDateTime.now(ZONE));

        checkMarketStatus(date)
                .doOnNext(resp -> log.debug("Market API raw response: {}", resp))
                // एक बार parse करो और List<MarketTiming> आगे भेजो
                .map(this::mapmarketTime)
                .doOnNext(ts -> log.info("Parsed market timings: {}", ts))
                // क्या NSE खुला है — लॉग भी करो
                .filter(timings -> {
                    boolean open = checkNseOpen(timings);
                    log.info("Is NSE open? {}", open);
                    return open;
                })
                // DB flush कर के आगे बढ़ो
                .flatMap(timings -> flushHistDatabase().thenReturn(timings))
                .doOnSuccess(v -> log.info("Database flush completed"))
                // instruments stream करो
                .flatMap(timings -> {
                    log.info("Successfully streamInstruments");
                    return instrumentService.streamInstruments();
                })
                .map(instrumentService::filterEquity)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(err -> log.error("Pipeline error", err))
                // अगर पूरा pipeline empty हुआ तो साफ़ log दिखाओ
                .switchIfEmpty(Mono.fromRunnable(() -> log.info("Market closed or no timings returned; pipeline ended without streaming instruments")))
                .subscribe(
                        result -> {
                            log.info("Successfully processed instruments");
                            // अगर यह blocking हो सकता है तो ध्यान रखें; चाहें तो scheduler बदलें
                            historicalDataService.manageHistoricalData(result);
                        },
                        error -> log.error("Critical error in daily market check pipeline", error));


    }


    private Mono<String> checkMarketStatus(String date) {
        return webClient.get()
                .uri(MARKET_STATUS_URL_TEMPLATE, Collections.singletonMap("date", date))
                .accept(MediaType.APPLICATION_JSON)
                // .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new RuntimeException("Client error checking market status")))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        Mono.error(new RuntimeException("Server error checking market status")))
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .doOnSuccess(response -> log.debug("Market status API response: {}", response))
                .doOnError(error -> log.error("Failed to check market status", error));
    }


    private Mono<Void> flushHistDatabase() {
        log.info("Starting database flush");
        return historicalCandleRepo.deleteAll()
                .then(instrumentRepo.deleteAll());
    }


    record MarketTiming(String exchange, ZonedDateTime start, ZonedDateTime end) {
    }

    private boolean checkNseOpen(List<MarketTiming> timings) {
        if (timings == null) return false;
        Map<String, MarketTiming> map = timings.stream().collect(Collectors.toMap(mt -> mt.exchange().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(kolkata);

        MarketTiming mt = map.get("NSE".toUpperCase(Locale.ROOT));
        if (mt == null) return false;

        ZonedDateTime start = mt.start();
        ZonedDateTime end = mt.end();
        log.info("  start {}  end{}", start, end);

        if (end.isBefore(start) || end.isEqual(start)) {
            return !now.isBefore(start) || !now.isAfter(end);
        } else {
            return !now.isBefore(start) && !now.isAfter(end);
        }
    }

    private List<MarketTiming> mapmarketTime(String jsonResponse) {
        JSONObject root = new JSONObject(jsonResponse);
        if (!"success".equalsIgnoreCase(root.optString("status", ""))) return List.of();

        JSONArray data = root.optJSONArray("data");
        if (data == null || data.isEmpty()) return List.of();

        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        List<MarketTiming> list = new ArrayList<>(data.length());

        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.getJSONObject(i);
            String exchange = o.optString("exchange", "").trim();
            long s = o.optLong("start_time", -1);
            long e = o.optLong("end_time", -1);
            if (exchange.isEmpty() || s < 0 || e < 0) continue;
            list.add(new MarketTiming(exchange, Instant.ofEpochMilli(s).atZone(kolkata), Instant.ofEpochMilli(e).atZone(kolkata)));
        }
        return list;
    }

}
