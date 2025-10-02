package com.ravi.stockscreener.scanservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ravi.stockscreener.model.Instrument;
import com.ravi.stockscreener.model.UpstoxInstrument;
import com.ravi.stockscreener.repo.InstrumentRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentService {
    public static final String INSTRUMENTS_URL = "https://assets.upstox.com/market-quote/instruments/exchange/complete.json.gz";
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final InstrumentRepo instrumentRepo;
    private final OkHttpClient okHttpClient;


    public Mono<JsonNode> downloadInstrumentJson() {
        return Mono.create(sink -> {
            log.info("Starting instrument download from {}", INSTRUMENTS_URL);
            Request req = new Request.Builder().url(INSTRUMENTS_URL).get().build();
            Call call = okHttpClient.newCall(req);

            // Cancel handling
            sink.onCancel(() -> {
                log.info("Download cancelled by subscriber");
                call.cancel();
            });

            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    sink.error(e); // cancel होने पर error भेजना safe है
                }

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response resp) {
                    try (resp; InputStream gz = new GZIPInputStream(resp.body().byteStream())) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] data = new byte[8192];
                        int nRead;
                        long totalRead = 0;
                        while ((nRead = gz.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                            totalRead += nRead;
                            if (totalRead % (1024 * 1024) < 8192) { // हर MB पर log
                                log.info("Downloaded approx {} MB", totalRead / (1024 * 1024));
                            }
                        }
                        buffer.flush();
                        JsonNode root = mapper.readTree(new ByteArrayInputStream(buffer.toByteArray()));
                        sink.success(root);
                    } catch (Exception ex) {
                        sink.error(ex);
                    }
                }
            });
        });
    }


    private byte[] gzipCompress(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(input);
        }
        return baos.toByteArray();
    }

    private String gunzipToString(byte[] gz) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
             InputStreamReader isr = new InputStreamReader(gis, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }


    public Mono<List<UpstoxInstrument>> streamInstruments() {
        log.info("streamInstruments ");
        return instrumentRepo.count()
                .flatMap(count -> {
                    log.info("instrumentRepo.count() returned {}", count);
                    if (count > 0) {
                        log.info("Instruments already exist in DB — loading all");
                        return instrumentRepo.findAll()
                                .flatMap(instrument -> {
                                    try {
                                        byte[] blob = instrument.getSymbol_json();
                                        if (blob == null || blob.length == 0) {
                                            throw new IllegalStateException("instrument.symbolBlob is empty");
                                        }
                                        String jsonText = gunzipToString(blob); // blocking -> boundedElastic below
                                        JsonNode node = mapper.readTree(jsonText);
                                        return Flux.fromIterable(parseJson(node));
                                    } catch (Exception e) {
                                        return Flux.error(e);
                                    }
                                })
                                .collectList()
                                .doOnNext(list -> log.info("Loaded {} instruments from DB", list.size()))
                                .doOnError(err -> log.error("Error loading instruments from DB: {}", err.toString(), err));
                    } else {
                        log.info("No instruments in DB — downloading and saving");
                        return downloadInstrumentJson()
                                .flatMap(jsonNode -> {
                                    byte[] gz = null;
                                    try {
                                        gz = gzipCompress(jsonNode.toString().getBytes(StandardCharsets.UTF_8));
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    Instrument instrument = new Instrument();
                                    instrument.setSymbol_json(gz);
                                    return instrumentRepo.save(instrument);
                                })
                                .flatMapMany(instrument -> {
                                    try {
                                        byte[] blob = instrument.getSymbol_json();
                                        if (blob == null || blob.length == 0) {
                                            throw new IllegalStateException("instrument.symbolBlob is empty");
                                        }
                                        String jsonText = gunzipToString(blob); // blocking -> boundedElastic below
                                        JsonNode node = mapper.readTree(jsonText);
                                        return Flux.fromIterable(parseJson(node));
                                    } catch (Exception e) {
                                        return Flux.error(e);
                                    }
                                })
                                .collectList()
                                .doOnNext(list -> log.info("Downloaded and saved {} instruments", list.size()))
                                .doOnError(err -> log.error("Error downloading/saving instruments: {}", err.toString(), err));
                    }
                })
                .doOnError(err -> log.error("streamInstruments() failed: {}", err.toString(), err));
    }

    private List<UpstoxInstrument> parseJson(JsonNode root) throws JsonProcessingException {
        //  log.info("parseJson {} instruments", root);
        List<UpstoxInstrument> out = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                // System.out.println(node);
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
        }
        return out;
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
