package com.ravi.stockscreener.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Date;


@RequiredArgsConstructor
@Data
@Table("strategy")
public class Strategys {
    @Id
    private Integer id;

    @Column("payload")
    private String payload;

    @Column("created_at")
    private String createdAt = new Date(System.currentTimeMillis()).toString();
}
