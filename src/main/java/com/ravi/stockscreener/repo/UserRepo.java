package com.ravi.stockscreener.repo;

import com.ravi.stockscreener.model.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepo extends ReactiveCrudRepository<User, Integer> {
}
