package com.ravi.stockscreener.repo;

import com.ravi.stockscreener.model.Instrument;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface InstrumentRepo extends ReactiveCrudRepository<Instrument, Integer> {
}
