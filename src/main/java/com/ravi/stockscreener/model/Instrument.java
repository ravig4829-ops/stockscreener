package com.ravi.stockscreener.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@RequiredArgsConstructor
@Table("instrument")
public class Instrument {
    @Id
    private Integer id;
    private byte[] symbol_json;
    private Instant createdAt;

}
