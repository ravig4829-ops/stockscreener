package com.ravi.stockscreener.repo;

import com.ravi.stockscreener.model.Strategys;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface StrategyRepository extends ReactiveCrudRepository<Strategys, Integer> {

    @Query("SELECT EXISTS(SELECT 1 FROM strategy WHERE payload = :payload)")
    Mono<Boolean> existsByPayload(String payload);

}
