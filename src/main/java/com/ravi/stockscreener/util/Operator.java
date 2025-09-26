package com.ravi.stockscreener.util;

import com.fasterxml.jackson.annotation.JsonValue;

public interface Operator {
    String getDisplayName();

    @JsonValue
    String getSymbol();


}
