package com.ravi.stockscreener.util;

import lombok.Getter;

@Getter
public enum Source {
    OPEN("Open", "open"),
    HIGH("High", "high"),
    LOW("Low", "low"),
    CLOSE("Close", "close"),
    VOLUME("Volume", "volume");


    private final String displayName;
    private final String symbol;

    Source(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }
}
