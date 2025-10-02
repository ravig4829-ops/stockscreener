package com.ravi.stockscreener.util;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = OperatorDeserializer.class)
public interface Operator {
    String getDisplayName();

    @JsonValue
    String getSymbol();


}
