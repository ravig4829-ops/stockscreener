package com.ravi.stockscreener.scanservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ravi.stockscreener.model.*;
import com.ravi.stockscreener.util.*;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import kotlin.Pair;
import kotlin.Triple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import reactor.adapter.rxjava.RxJava3Adapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.ravi.stockscreener.model.Segment.EQUITY;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScanService {

    private final InstrumentService instrumentService;
    private final HistoricalDataService historicalDataService;
    private final ObjectMapper mapper;


    public Mono<ResponseEntity<String>> scan(Strategy strategy, boolean backgroundScan) {
        return startScan(strategy, backgroundScan)
                .collectList()
                .map(list -> {
                    if (list.isEmpty()) {
                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("success", "false");
                        jsonObject.put("data", "No matches found");
                        return ResponseEntity.ok(jsonObject.toString());
                    } else {
                        // join results into single text body (you can change to JSON if preferred)
                        try {
                            String json = mapper.writeValueAsString(list);
                            JSONObject jsonObject = new JSONObject();
                            jsonObject.put("success", "true");
                            jsonObject.put("data", json);
                            return ResponseEntity.ok(jsonObject.toString());
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    }
                })
                .onErrorResume(e -> {
                    log.error("Scan failed", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("Scan failed: " + e.getMessage()));
                });
    }

    private Flux<Triple<UpstoxInstrument, Boolean, List<String>>> startScan(Strategy strategy, boolean backgroundScan) {
        return instrumentService
                .streamInstruments()
                .flatMapIterable(upstoxInstruments -> {
                    List<UpstoxInstrument> filtered = null;
                    if (Objects.requireNonNull(strategy.getSegment()) == EQUITY) {
                        filtered = instrumentService.filterEquity(upstoxInstruments);
                        // Add other cases
                    }
                    return filtered;
                })
                .flatMap(inst -> RxJava3Adapter.singleToMono(evaluateAllConditionsForInstrument(strategy, inst)
                        .map(pair -> new Triple<>(inst, pair.getFirst(), pair.getSecond()))
                        .onErrorReturnItem(new Triple<>(inst, false, List.of("evaluation error")))))
                .filter(Triple::getSecond)
                .map(triple -> {
                    log.info("MATCH: {} ({})", triple.getFirst().name(), triple.getFirst().instrumentKey());
                    if (!triple.getThird().isEmpty()) {
                        log.info("  debug notes: {}", triple.getThird());
                    }
                    return triple;
                });

    }


    public Single<Pair<Boolean, List<String>>> evaluateAllConditionsForInstrument(Strategy strategy, UpstoxInstrument inst) {
        return Observable.fromIterable(strategy.getConditions())
                .flatMapSingle(cond ->
                        evaluateConditionForInstrumentDebug(cond, inst)
                                .map(result -> new Pair<>(result, cond.toString()))
                                .onErrorReturnItem(new Pair<>(false, cond.toString()))
                )
                .toList()
                .map(list -> {
                    boolean all = true;
                    List<String> failed = new ArrayList<>();
                    for (Pair<Boolean, String> p : list) {
                        if (p == null || p.getFirst() == null || !p.getFirst()) {
                            all = false;
                            if (p != null) failed.add(p.getSecond());
                        }
                    }
                    return new Pair<>(all, failed);
                });
    }

    private Single<Boolean> evaluateConditionForInstrumentDebug(Condition condition, UpstoxInstrument inst) {
        Operand mainOperand = condition.getMainOperand();
        List<Operand> others = condition.getOthersOperand();

        String header = ">>> SYMBOL: " + inst.name() + " (" + inst.instrumentKey() + ")  | Condition: " + condition;
        log.debug(header);

        if (others == null || others.isEmpty()) {
            log.debug("  -> othersOperand null/empty => condition returns false");
            log.debug("-----------------------------------------------------------------");
            return Single.just(false);
        }

        Single<Triple<Num, Num, Operand>> mainSingle = getIndicator(inst, mainOperand)
                .doOnSuccess(triple -> {
                    log.debug("  Main operand fetched: {}", describeOperand(mainOperand));
                    log.debug("    currentMain = {}, prevMain = {}", triple.getFirst(), triple.getSecond());
                });
        Observable<Triple<Num, Num, Operand>> othersObs = Observable.fromIterable(others)
                .concatMapSingle(op -> getIndicator(inst, op)
                        .doOnSuccess(t -> {
                            log.debug("  Other operand fetched: {}", describeOperand(op));
                            log.debug("    current = {}, prev = {}  (operator on this operand = '{}')", t.getFirst(), t.getSecond(), op.getOperator());
                        }));


        Single<Pair<Num, Num>> combinedOthersSingle = othersObs.toList().map(triples -> {
            if (triples.isEmpty()) {
                log.debug("  -> no other triples (unexpected).");
                return new Pair<>(null, null);
            }

            // debug: print raw operands
            for (int k = 0; k < triples.size(); k++) {
                Triple<Num, Num, Operand> t = triples.get(k);
                log.debug("    other[{}] = {}  current={} prev={}", k, describeOperand(t.getThird()), t.getFirst() == null ? "null" : t.getFirst(), t.getSecond() == null ? "null" : t.getSecond());
            }

            Num currentCombined = triples.get(0).getFirst();
            Num prevCombined = triples.get(0).getSecond();

            for (int i = 1; i < triples.size(); i++) {
                Triple<Num, Num, Operand> curTriple = triples.get(i);
                Operand prevOperand = triples.get(i - 1).getThird(); // operator stored on previous operand by your design
                ArithmeticOperator operator = prevOperand == null ? null : (ArithmeticOperator) prevOperand.getOperator();

                if (operator == null) {
                    log.debug("      WARNING: operator is null for previous operand: {} — cannot combine further. Returning null RHS.", describeOperand(prevOperand));
                    // Make RHS invalid so condition will be false but no crash
                    return new Pair<>(null, null);
                }

                Num newCurrent = applyArithmetic(currentCombined, curTriple.getFirst(), operator);
                Num newPrev = applyArithmetic(prevCombined, curTriple.getSecond(), operator);

              /*  System.out.println("      => after op: newCurrent=" + (newCurrent == null ? "null" : newCurrent)
                        + " newPrev=" + (newPrev == null ? "null" : newPrev));*/

                currentCombined = newCurrent;
                prevCombined = newPrev;

                // if at any point both become null, we can stop early
                if (currentCombined == null && prevCombined == null) {
                    System.out.println("      Both combined values became null — stopping combine early.");
                    return new Pair<>(null, null);
                }
            }

            /*System.out.println("  Final combined RHS: currentRhs=" + (currentCombined == null ? "null" : currentCombined)
                    + " prevRhs=" + (prevCombined == null ? "null" : prevCombined));*/
            return new Pair<>(currentCombined, prevCombined);
        });


        return Single.zip(mainSingle, combinedOthersSingle, (mainTriple, combinedPair) -> {
            Num currentMain = mainTriple.getFirst();
            Num prevMain = mainTriple.getSecond();
            if (currentMain == null && prevMain == null) {
                System.out.println("Skipping condition due to no data available for operand: " + mainTriple.getThird());
                return false; // condition fails safely
            }

            Num currentRhs = combinedPair.getFirst();
            Num prevRhs = combinedPair.getSecond();
            ComparisonOperator rawOp = (ComparisonOperator) mainOperand.getOperator();
            // String operator = mapOperator(rawOp);

            //System.out.println("  Evaluating: main=" + currentMain + "  operator='" + rawOp + "' (mapped='" + operator + "')  rhs=" + currentRhs);

            boolean result;
            try {
                // Final decision by ConditionEvaluator — single source of truth.
                result = evaluateCondition(currentMain, currentRhs, prevMain, prevRhs, rawOp);
            } catch (Exception ex) {
                log.error("    ERROR while evaluating: {}", ex.getMessage());
                result = false;
            }
            // System.out.println("  => Condition evaluation result: " + result);
            // System.out.println("-----------------------------------------------------------------");
            return result;
        }).onErrorReturn(throwable -> {
            log.error("  Exception while fetching indicators or combining: {}", String.valueOf(throwable));
            log.error("-----------------------------------------------------------------");
            throwable.printStackTrace(System.err);
            return false;
        });
    }


    public boolean evaluateCondition(Num currentLeft, Num currentRight, Num prevLeft, Num prevRight, ComparisonOperator operator) {
        // basic null-safety
        if (operator == null) {
            throw new IllegalArgumentException("Operator is null");
        }
        log.debug("  currentLeft  currentRight prevLeft  prevRight operator {}{}{}{}{}", currentLeft, currentRight, prevLeft, prevRight, operator);
        // normalize operator text (you can expand synonyms here)
        return switch (operator) {
            case GREATER -> currentLeft.isGreaterThan(currentRight);
            case LESS -> currentLeft.isLessThan(currentRight);
            case EQUAL -> currentLeft.isEqual(currentRight);
            case GREATER_EQUAL -> currentLeft.isGreaterThanOrEqual(currentRight);
            case LESS_EQUAL -> currentLeft.isLessThanOrEqual(currentRight);
            case NOT_EQUAL -> !currentLeft.isEqual(currentRight);
            case CROSSOVER -> {
                if (prevLeft == null || prevRight == null) yield false;
                // prevLeft <= prevRight  && currentLeft > currentRight
                yield (prevLeft.isLessThan(prevRight) || prevLeft.isEqual(prevRight))
                        && currentLeft.isGreaterThan(currentRight);
                // prevLeft <= prevRight  && currentLeft > currentRight
            }
            case CROSSUNDER -> {
                if (prevLeft == null || prevRight == null) yield false;
                // prevLeft >= prevRight && currentLeft < currentRight
                yield (prevLeft.isGreaterThan(prevRight) || prevLeft.isEqual(prevRight))
                        && currentLeft.isLessThan(currentRight);
                // prevLeft >= prevRight && currentLeft < currentRight
            }
        };
    }


    private String describeOperand(Operand op) {
        if (op == null) return "null";
        return String.format("(%s name=%s timeframe=%s operator=%s value=%s)", op, op.getOperator(), op.getTimeframe(), op.getOperator(), op);
    }


    // Helper method for safe arithmetic operations
    private Num applyArithmetic(Num a, Num b, ArithmeticOperator op) {
        if (a == null || b == null) return null;
        return switch (op) {
            case ADD -> a.plus(b);
            case SUBTRACT -> a.minus(b);
            case MULTIPLY -> a.multipliedBy(b);
            case DIVIDE -> b.isZero() ? null : a.dividedBy(b);
            default -> null;
        };
    }


    // Enhanced to handle historical data requests
    private @NonNull Single<Triple<Num, Num, Operand>> getIndicator(UpstoxInstrument upstoxInstrument, Operand operand) {
        if (operand instanceof InputsOperand inputsOperand) {
            // For constant inputs, current and previous values are the same
            Num value = DecimalNum.valueOf(inputsOperand.getValue());
            return Single.just(new Triple<>(value, value, operand));
        } else {
            return RxJava3Adapter.monoToSingle(ensureCandleData(upstoxInstrument.instrumentKey(), operand.getTimeframe())
                    .map(barSeries -> {
                        log.debug(" bars available for {} / tf={} — skipping this operand.", upstoxInstrument.name(), barSeries);
                        Indicator<Num> indicator = IndicatorFactory.buildIndicator(barSeries, operand);
                        int barCount = barSeries.getBarCount();
                        if (barCount <= 0) {
                            log.debug("No bars available for {} / tf={} — skipping this operand.", upstoxInstrument.name(), operand.getTimeframe());
                            return new Triple<>(null, null, operand);
                        }
                        // default: latest and previous
                        int currentIdx;
                        int prevIdx;

                        String tf = operand.getTimeframe();
                        if (tf != null && tf.startsWith("-")) {
                            // expect formats like "-20d", "-1d", "-5m", "-2h"
                            String clean = tf.substring(1).trim(); // remove leading '-'
                            if (clean.isEmpty()) {
                                throw new IllegalArgumentException("Invalid negative timeframe: " + tf);
                            }

                            // last character is unit letter (m/h/d) — but we treat offset as number of bars ago.
                            // e.g. "-20d" -> offset = 20 bars ago (your barSeries is already in that timeframe)
                            char last = clean.charAt(clean.length() - 1);
                            String numPart = clean;
                            if (!Character.isDigit(last)) {
                                numPart = clean.substring(0, clean.length() - 1);
                            }
                            int offset;
                            try {
                                offset = Integer.parseInt(numPart);
                                if (offset < 0) throw new NumberFormatException("negative offset");
                            } catch (NumberFormatException ex) {
                                throw new IllegalArgumentException("Invalid offset in timeframe '" + tf + "'", ex);
                            }

                            // correct index formula: latest index = barCount - 1, so offset N -> idx = barCount - 1 - N
                            currentIdx = barCount - 1 - offset;
                            prevIdx = currentIdx - 1;
                        } else {
                            // normal latest
                            currentIdx = barCount - 1;
                            prevIdx = barCount - 2;
                        }
                        // bounds checks
                        if (currentIdx < 0 || currentIdx >= barCount) {
                            log.debug("Skipping operand '{}' [{}] — required bars = {}, available = {}", operand, operand.getTimeframe(), operand.getTimeframe() + 1, barCount);
                            return new Triple<>(null, null, operand);
                        }

                        Num current = Objects.requireNonNull(indicator).getValue(currentIdx);
                        Num prev = prevIdx >= 0 ? indicator.getValue(prevIdx) : null;
                        return new Triple<>(current, prev, operand);
                    }));
        }
    }


    private Mono<BaseBarSeries> ensureCandleData(String upstoxInstrument, String timeframe) {
        log.debug("timeframe    {}", timeframe);
        return historicalDataService.ensureCandleData(upstoxInstrument, timeframe);
    }


    public Flux<ServerSentEvent<String>> startScanStream(Strategy strategy, boolean backgroundScan) {
        // Run scan on boundedElastic so heavy/IO work does not block Netty event-loop
        Flux<Triple<UpstoxInstrument, Boolean, List<String>>> rawMatches =
                startScan(strategy, backgroundScan)
                        .subscribeOn(Schedulers.boundedElastic())
                        .share(); // share so multiple downstreams don't re-run the scan

        // Map each matching triple -> SSE match event with JSON payload
        Flux<ServerSentEvent<String>> matchEvents = rawMatches
                .map(triple -> {
                    UpstoxInstrument inst = triple.getFirst();
                    List<String> notes = triple.getThird();
                    JSONObject payload = new JSONObject();
                    payload.put("type", "match");
                    payload.put("symbol", inst.name());
                    payload.put("instrumentKey", inst.instrumentKey());
                    payload.put("notes", notes);
                    String out = payload.toString();
                    return ServerSentEvent.<String>builder()
                            .event("match")
                            .id(inst.instrumentKey())
                            .data(out)
                            .build();
                });

        // Heartbeat every 15s, stop heartbeats once matches stream completes
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(t -> ServerSentEvent.<String>builder().event("heartbeat").data("ping").build())
                .takeUntilOther(rawMatches.then()); // stop pinging after matches complete

        // After matches complete, emit a single 'complete' event
        Flux<ServerSentEvent<String>> completeEvent = Flux.defer(() ->
                Flux.just(ServerSentEvent.<String>builder().event("complete").data("done").build()));

        // Compose: merge matches + heartbeat, then concat complete. Convert errors to 'error' event.
        return Flux.merge(matchEvents, heartbeat)
                .concatWith(completeEvent)
                .onErrorResume(err -> {
                    log.error("Scan stream failed", err);
                    String msg = "Scan error: " + (err.getMessage() == null ? err.toString() : err.getMessage());
                    ServerSentEvent<String> ev = ServerSentEvent.<String>builder()
                            .event("error")
                            .data(msg)
                            .build();
                    // return a single error event and then complete the flux
                    return Flux.just(ev);
                })
                .doFinally(sig -> log.info("SSE stream finished with signal: {}", sig));
    }
}
