package com.ravi.stockscreener.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Date;

@RequiredArgsConstructor
@Data
@Table("strategy_result")
public class Strategy_Result {
    @Id
    private Integer id;

    @Column("strategy_name")
    private String strategy_name;

    @Column("symbol_name")
    private String symbol_name;


    @Column("created_at")
    private String createdAt = new Date(System.currentTimeMillis()).toString();


}
