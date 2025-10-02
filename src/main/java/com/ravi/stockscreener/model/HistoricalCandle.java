package com.ravi.stockscreener.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
@Table("historical_candle")
public class HistoricalCandle {
    @Id
    private Integer id;

    @Column("created_at")
    private Instant createdAt;
    @Column("json")
    private String json;
    @Column("key")
    private String key;

}
