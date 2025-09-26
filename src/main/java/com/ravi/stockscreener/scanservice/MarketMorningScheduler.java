package com.ravi.stockscreener.scanservice;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
    private final ReactiveStringRedisTemplate redis;

     @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
    public void runDailyMorningCheck() {
        String date = LocalDate.now(ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE); // yyyy-MM-dd
        log.info("Running morning market check for {} at {}", date, ZonedDateTime.now(ZONE));

        try {
            String resp = webClient
                    .get()
                    .uri(MARKET_STATUS_URL_TEMPLATE, Collections.singletonMap("date", date))
                    .accept(MediaType.APPLICATION_JSON)
                    // .header("Authorization", "Bearer " + accessToken) // add if required
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new RuntimeException("Upstox error: " + body)))
                    )
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10)); // still blocks but with explicit timeout

            if (resp == null) {
                log.warn("Market status API returned null");
                return;
            }

            if (!checkNseOpen(mapmarketTime(resp))) {
                log.info("Market closed today according to API. Response: {}", resp);
                return;
            }

            flushRedisDatabase();

            instrumentService.streamInstruments()
                    .map(instrumentService::filterEquity)
                    .doOnNext(list -> {
                        try {
                            historicalDataService.manageHistoricalData(list);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        log.info("Fetched {} instruments", list.size());
                    })
                    .doOnError(err -> log.error("Failed to load instruments at startup", err));

            log.info("Market timings response: {}", resp);
        } catch (Exception e) {
            log.error("runDailyMorningCheck error", e);
        }
    }

    private void flushRedisDatabase() {
        try {
            redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb();
            log.warn("FLUSHDB executed - ENTIRE Redis database cleared!");
        } catch (Exception e) {
            log.error("Error flushing redis db", e);
        }
    }


    record MarketTiming(String exchange, ZonedDateTime start, ZonedDateTime end) {
    }

    private boolean checkNseOpen(List<MarketTiming> timings) {
        if (timings == null) return false;

        Map<String, MarketTiming> map = timings.stream()
                .collect(Collectors.toMap(
                        mt -> mt.exchange().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a
                ));

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
            list.add(new MarketTiming(exchange,
                    Instant.ofEpochMilli(s).atZone(kolkata),
                    Instant.ofEpochMilli(e).atZone(kolkata)));
        }
        return list;
    }

}
