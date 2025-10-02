package com.ravi.stockscreener.repo;

import com.ravi.stockscreener.model.Strategy_Result;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Strategy_ResultRepo extends ReactiveCrudRepository<Strategy_Result, Integer> {
}
