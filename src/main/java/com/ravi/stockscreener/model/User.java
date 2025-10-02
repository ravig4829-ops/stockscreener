package com.ravi.stockscreener.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Date;

@RequiredArgsConstructor
@Data
@Table("user")
public class User {
    @Id
    private Integer id;

    @Column("accesstoken")
    private String accesstoken;

    @Column("created_at")
    private String createdAt = new Date(System.currentTimeMillis()).toString();

    @Column("expiry")
    private Instant expiry;


}
