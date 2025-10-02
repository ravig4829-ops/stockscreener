package com.ravi.stockscreener.repo;


import com.ravi.stockscreener.model.HistoricalCandle;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface HistoricalCandleRepo extends ReactiveCrudRepository<HistoricalCandle, Integer> {
    Mono<Boolean> existsByKey(String key);                  // derived query

    Flux<HistoricalCandle> findByKey(String key);

    @Query("SELECT * FROM historical_candle WHERE \"key\" = :key ORDER BY created_at DESC")
    Mono<HistoricalCandle> findByKeyOrderByCreatedAtDesc(String key);

    Mono<Void> deleteByKey(String key);
}

