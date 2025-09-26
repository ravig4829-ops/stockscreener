package com.ravi.stockscreener.repo;

import com.ravi.stockscreener.model.Strategy_Result;
import com.ravi.stockscreener.model.Strategys;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface Strategy_ResultRepo extends ReactiveCrudRepository<Strategy_Result, Integer> {
}
