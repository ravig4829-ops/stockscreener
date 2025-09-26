package com.ravi.stockscreener.model;


import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class Tick {
    public String symbol;
    public double price;
    public long volume;
    public long timestamp;
}
