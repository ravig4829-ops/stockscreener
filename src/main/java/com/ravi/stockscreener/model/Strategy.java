package com.ravi.stockscreener.model;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Date;
import java.util.List;


@RequiredArgsConstructor
@Data
public class Strategy {

    private Integer id;
    private String name;
    private Segment segment;
    private List<Condition> conditions;
    private boolean enabled;
    private String createdAt = new Date(System.currentTimeMillis()).toString();

}