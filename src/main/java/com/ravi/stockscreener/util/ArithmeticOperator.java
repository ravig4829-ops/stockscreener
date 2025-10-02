package com.ravi.stockscreener.util;

// Operator.java


import lombok.Getter;

@Getter
public enum ArithmeticOperator implements Operator {
    ADD("ADD", "+"),
    SUBTRACT("SUBTRACT", "-"),
    MULTIPLY("MULTIPLY", "*"),
    DIVIDE("DIVIDE", "/"),
    MODULO("MODULO", "%"),
    POWER("POWER", "^");

    private final String displayName;
    private final String symbol;

    ArithmeticOperator(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }


    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getSymbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static ArithmeticOperator fromSymbol(String s) {
        if (s == null) return null;
        for (ArithmeticOperator a : values()) {
            if (a.getSymbol().equals(s) || a.getDisplayName().equalsIgnoreCase(s) || a.name().equalsIgnoreCase(s)) {
                return a;
            }
        }
        return null;
    }

}

