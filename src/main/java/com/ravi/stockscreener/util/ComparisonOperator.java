package com.ravi.stockscreener.util;


import lombok.Getter;
import reactor.util.annotation.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public enum ComparisonOperator implements Operator {
    GREATER("GREATER", ">"),
    LESS("LESS", "<"),
    EQUAL("EQUAL", "=="),
    GREATER_EQUAL("GREATER_EQUAL", ">="),
    LESS_EQUAL("LESS_EQUAL", "<="),
    NOT_EQUAL("NOT_EQUAL", "!="),
    CROSSOVER("CROSSOVER", "Crossover"),
    CROSSUNDER("CROSSUNDER", "Crossunder");

    private final String displayName;
    private final String symbol; // for text comparison or display

    ComparisonOperator(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    @NonNull
    @Override
    public String toString() {
        return displayName;
    }

    public static ComparisonOperator fromSymbol(String symbol) {
        if (symbol == null) return null;
        for (ComparisonOperator o : values()) {
            if (o.symbol.equals(symbol)) return o;
        }
        return null;
    }

    public static ComparisonOperator fromDisplayName(String name) {
        if (name == null) return null;
        for (ComparisonOperator o : values()) {
            if (o.displayName.equalsIgnoreCase(name) || o.name().equalsIgnoreCase(name)) return o;
        }
        return null;
    }

}

